package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{RemoteContextError, UnexpectedJsonLd, UnexpectedJsonLdContext}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import ch.epfl.bluebrain.nexus.delta.rdf.{tryOrConversionErr, RdfError}
import com.github.jsonldjava.core.{Context, DocumentLoader, JsonLdProcessor, JsonLdOptions => JsonLdJavaOptions}
import com.github.jsonldjava.utils.JsonUtils
import io.circe.{parser, Json, JsonObject}
import monix.bio.IO
import org.apache.jena.iri.IRI
import org.apache.jena.rdf.model.{Model, ModelFactory}
import org.apache.jena.riot.RDFFormat.{JSONLD_EXPAND_FLAT => EXPAND}
import org.apache.jena.riot.system.StreamRDFLib
import org.apache.jena.riot.{JsonLDWriteContext, Lang, RDFParser, RDFWriter}

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
  * Json-LD high level API implementation by Json-LD Java library
  */
object JsonLdJavaApi extends JsonLdApi {

  System.setProperty(DocumentLoader.DISALLOW_REMOTE_CONTEXT_LOADING, "true")

  override private[rdf] def compact[Ctx <: JsonLdContext](input: Json, ctx: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, (JsonObject, Ctx)] =
    for {
      obj          <- tryOrConversionErr(JsonUtils.fromString(input.noSpaces), "building input")
      ctxObj       <- tryOrConversionErr(JsonUtils.fromString(ctx.noSpaces), "building context")
      options      <- remoteContextLoader(input, ctx).map(toOpts)
      compacted    <- tryOrConversionErr(JsonUtils.toString(JsonLdProcessor.compact(obj, ctxObj, options)), "compacting")
      compactedObj <- IO.fromEither(toJsonObjectOrErr(compacted))
      ctxValue     <- context(ctx, f, options)
    } yield (compactedObj, ctxValue)

  override private[rdf] def expand(
      input: Json
  )(implicit options: JsonLdOptions, resolution: RemoteContextResolution): IO[RdfError, Seq[JsonObject]] =
    for {
      obj            <- tryOrConversionErr(JsonUtils.fromString(input.noSpaces), "building input")
      options        <- remoteContextLoader(input).map(toOpts)
      expanded       <- tryOrConversionErr(JsonUtils.toString(JsonLdProcessor.expand(obj, options)), "expanding")
      expandedSeqObj <- IO.fromEither(toSeqJsonObjectOrErr(expanded))
    } yield expandedSeqObj

  override private[rdf] def frame[Ctx <: JsonLdContext](input: Json, frame: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, (JsonObject, Ctx)] =
    for {
      obj       <- tryOrConversionErr(JsonUtils.fromString(input.noSpaces), "building input")
      ff        <- tryOrConversionErr(JsonUtils.fromString(frame.noSpaces), "building frame")
      options   <- remoteContextLoader(input, frame).map(toOpts)
      framed    <- tryOrConversionErr(JsonUtils.toString(JsonLdProcessor.frame(obj, ff, options)), "framing")
      framedObj <- IO.fromEither(toJsonObjectOrErr(framed))
      ctxValue  <- context(frame, f, options)
    } yield (framedObj, ctxValue)

  // TODO: Right now this step has to be done from a JSON-LD expanded document,
  // since the DocumentLoader cannot be passed to Jena yet: https://issues.apache.org/jira/browse/JENA-1959
  override private[rdf] def toRdf(
      input: Json
  )(implicit opts: JsonLdOptions, resolution: RemoteContextResolution): IO[RdfError, Model] = {
    val model       = ModelFactory.createDefaultModel()
    val initBuilder = RDFParser.create.fromString(input.noSpaces).lang(Lang.JSONLD)
    val builder     = opts.base.fold(initBuilder)(base => initBuilder.base(base.toString))
    tryOrConversionErr(builder.parse(StreamRDFLib.graph(model.getGraph)), "toRdf").as(model)
  }

  override private[rdf] def fromRdf(input: Model)(implicit options: JsonLdOptions): IO[RdfError, Seq[JsonObject]] = {
    val c = new JsonLDWriteContext()
    c.setOptions(toOpts())
    for {
      expanded       <- tryOrConversionErr(RDFWriter.create.format(EXPAND).source(input).context(c).asString(), "fromRdf")
      expandedSeqObj <- IO.fromEither(toSeqJsonObjectOrErr(expanded))
    } yield expandedSeqObj
  }

  override private[rdf] def context[Ctx <: JsonLdContext](value: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      resolution: RemoteContextResolution
  ): IO[RdfError, Ctx] =
    f match {
      case ContextFields.Skip    => context(value, f, toOpts())
      case ContextFields.Include => remoteContextLoader(value).flatMap(dl => context(value, f, toOpts(dl)))
    }

  private def context[Ctx <: JsonLdContext](
      value: Json,
      f: ContextFields[Ctx],
      options: JsonLdJavaOptions
  ): IO[RdfError, Ctx] = {
    val cxtValue = value.topContextValueOrEmpty
    f match {

      case ContextFields.Skip => IO.now(RawJsonLdContext(cxtValue))

      case ContextFields.Include =>
        IO.fromTry(Try(new Context(options).parse(JsonUtils.fromString(cxtValue.noSpaces))))
          .leftMap(err => UnexpectedJsonLdContext(err.getMessage))
          .map { ctx =>
            val pm      = ctx.getPrefixes(true).asScala.toMap.map { case (k, v) => k -> iri"$v" }
            val aliases = (ctx.getPrefixes(false).asScala.toMap -- pm.keySet).map { case (k, v) => k -> iri"$v" }

            ExtendedJsonLdContext(cxtValue, getIri(ctx, keywords.base), getIri(ctx, keywords.vocab), aliases, pm)
          }
    }
  }

  private def remoteContextLoader(
      jsons: Json*
  )(implicit resolution: RemoteContextResolution): IO[RdfError, DocumentLoader] =
    IO.parTraverseUnordered(jsons)(resolution(_))
      .leftMap(RemoteContextError)
      .map {
        _.foldLeft(Map.empty[IRI, Json])(_ ++ _).foldLeft(new DocumentLoader()) {
          case (dl, (iri, cxt)) => dl.addInjectedDoc(iri.toString, Json.obj(keywords.context -> cxt).noSpaces)
        }
      }

  private def toOpts(dl: DocumentLoader = new DocumentLoader)(implicit options: JsonLdOptions): JsonLdJavaOptions = {
    val opts = new JsonLdJavaOptions()
    options.base.foreach(b => opts.setBase(b.toString))
    opts.setCompactArrays(options.compactArrays)
    opts.setCompactArrays(options.compactArrays)
    opts.setProcessingMode(options.processingMode)
    opts.setProduceGeneralizedRdf(options.produceGeneralizedRdf)
    opts.setPruneBlankNodeIdentifiers(options.pruneBlankNodeIdentifiers)
    opts.setUseNativeTypes(options.useNativeTypes)
    opts.setUseRdfType(options.useRdfType)
    opts.setEmbed(options.embed)
    opts.setExplicit(options.explicit)
    opts.setOmitGraph(options.omitGraph)
    opts.setOmitDefault(options.omitDefault)
    opts.setRequireAll(options.requiredAll)
    opts.setDocumentLoader(dl)
    opts
  }

  private def getIri(ctx: Context, key: String): Option[IRI]                  =
    Option(ctx.get(key)).collectFirstSome { case str: String => str.toIri.toOption }

  private def toJsonObjectOrErr(string: String): Either[RdfError, JsonObject] =
    for {
      json <- parser.parse(string).leftMap(err => UnexpectedJsonLd(err.getMessage()))
      obj  <- json.asObject.toRight(UnexpectedJsonLd("Expected a Json Object"))
    } yield obj

  private def toSeqJsonObjectOrErr(string: String): Either[RdfError, Seq[JsonObject]] =
    for {
      json   <- parser.parse(string).leftMap(err => UnexpectedJsonLd(err.getMessage()))
      objSeq <- json.asArray
                  .flatMap(_.foldM(Vector.empty[JsonObject])((seq, json) => json.asObject.map(seq :+ _)))
                  .toRight(UnexpectedJsonLd("Expected a sequence of Json Object"))
    } yield objSeq
}
