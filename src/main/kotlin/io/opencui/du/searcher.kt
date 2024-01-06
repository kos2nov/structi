package io.opencui.du

import io.opencui.core.Dispatcher
import org.apache.lucene.document.*
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.IndexWriterConfig.OpenMode
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.Directory
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.store.RAMDirectory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.ArrayList

/**
 * We assume the training expression will be indexed the code generation phase.
 * And this code is used to do search to reduce the number expression that we need
 * to go through.
 *
 * Expression: "I like to watch a <Moive>"
 * context: Frame that we are in, some expression are weak, and only be triggered where context is right.
 * target: the frame that this expression is attached too, payload
 *
 * if context is default, but target is not null, expression is triggering
 * if context is not null, and target is the same, expression is not trigger, but target is context.
 * if context is not null, and target is not same, we can deal with case for confirmation.
 */

data class ScoredDocument(override var score: Float, val document: Document) : Triggerable {
    override val utterance: String = document.getField(UTTERANCE).stringValue()
    override var typedExpression: String = document.getField(EXPRESSION).stringValue()
    override val ownerFrame: String = document.getField(OWNER).stringValue()
    override val contextFrame: String? = document.getField(CONTEXTFRAME)?.stringValue()
    val slotTypes: List<String> = document.getFields(SLOTTYPE).map {it.stringValue()}
    override val entailedSlots: List<String> = document.getFields(PARTIALEXPRESSION).map {it.stringValue() }
    override val label: String? = if (document.get(LABEL) == null) "" else document.get(LABEL)

    // whether it is exact match.
    override var exactMatch: Boolean = false

    // The next two are used for potential exect match.
    override var possibleExactMatch: Boolean = false
    override var guessedSlot: DUSlotMeta? = null
    override fun clone(): Triggerable { return this.copy() }

    fun isCompatible(type: String, packageName: String?) : Boolean {
        return ownerFrame == "${packageName}.${type}"
    }

    fun probes(bot: DUMeta) : String {

        return AngleSlotRegex.replace(typedExpression) {
            val slotTypeName = it.value.removePrefix("<").removeSuffix(">").removeSurrounding(" ")
            val triggers = bot.getTriggers(slotTypeName)
            if (triggers.isNullOrEmpty()) {
                // there are templated expressions that does not have trigger before application.
                "< $slotTypeName >"
            } else {
                "< ${triggers[0]} >"
            }
        }
    }

    fun slotNames(): List<String> {
        return AngleSlotRegex
            .findAll(utterance)
            .map { it.value.substring(1, it.value.length - 1) }   // remove leading and trailing $
            .toList()
    }

    companion object {
        const val PROBE = "probe"
        const val UTTERANCE = "utterance"
        const val OWNER = "owner"
        const val OWNERSLOT = "owner_slot"
        const val SLOTS = "slots"
        const val LABEL = "label"
        const val SLOTTYPE = "slotType"
        const val CONTEXT = "context"
        const val CONTEXTFRAME = "context_frame"
        const val CONTEXTSLOT = "context_slot"
        const val EXPRESSION = "expression"
        const val PARTIALEXPRESSION = "partial_application"
        private val AngleSlotPattern = Pattern.compile("""<(.+?)>""")
        private val AngleSlotRegex = AngleSlotPattern.toRegex()
        val logger: Logger = LoggerFactory.getLogger(Expression::class.java)
    }
}

/**
 * This allows us to separate the index logic from parsing logic.
 */
data class IndexBuilder(val dir: Directory, val lang: String) {
    val analyzer = LanguageAnalyzer.get(lang)
    val iwc = IndexWriterConfig(analyzer).apply{openMode = OpenMode.CREATE}
    val writer = IndexWriter(dir, iwc)

    fun index(doc: Document) {
        writer.addDocument(doc)
    }
    fun close() {
        writer.close()
    }
}

fun Expression.toDoc() : Document {
    val expr = this
    val doc = Document()
    // Use the trigger based probes so that it works for multilingual.

    val expression = Expression.buildTypedExpression(expr.utterance, expr.owner, expr.bot)

    // Instead of embedding into expression, use StringField.
    val slotTypes = buildSlotTypes()
    for (slotType in slotTypes) {
        doc.add(StoredField(ScoredDocument.SLOTTYPE, slotType))
    }
    // "expression" is just for searching
    doc.add(TextField(ScoredDocument.EXPRESSION, expression, Field.Store.YES))
    doc.add(StoredField(ScoredDocument.UTTERANCE, expr.utterance))


    // We assume that expression will be retrieved based on the context.
    // this assume that there are different values for context:
    // default, active frame, active frame + requested slot.
    Expression.logger.info("context: ${buildFrameContext()}, expression: $expression, ${expr.utterance.lowercase(Locale.getDefault())}")
    doc.add(StringField(ScoredDocument.CONTEXT, buildFrameContext(), Field.Store.YES))

    if (context?.slot != null) {
        Expression.logger.info("context slot ${context.slot}")
        doc.add(StoredField(ScoredDocument.CONTEXTFRAME, context.frame))
        doc.add(StoredField(ScoredDocument.CONTEXTSLOT, context.slot))
    }
    doc.add(StoredField(ScoredDocument.OWNER, expr.owner))


    // TODO: verify and remove the unused code, when we handle pronouns.

    if (partialApplications != null) {
        Expression.logger.info("entailed slots: ${partialApplications.joinToString(",")}")
        for (entailedSlot in partialApplications) {
            doc.add(StringField(ScoredDocument.PARTIALEXPRESSION, entailedSlot, Field.Store.YES))
        }
    }

    if (!expr.label.isNullOrEmpty())
        doc.add(StringField(ScoredDocument.LABEL, expr.label, Field.Store.YES))
    return doc
}


/**
 * There three type of expressions:`
 * Slot label expression: We want to go to <destination>
 * Slot type expression: We want to go to <City>
 * slot normalized expression: We want to go to <chu fa di> // for chinese, notice is it is in language dependent form.
 */

data class ExpressionSearcher(val agent: DUMeta) {
    val k: Int = 32
    private val maxFromSame: Int = 4
    private val analyzer = LanguageAnalyzer.get(agent.getLang())
    private val reader: DirectoryReader = DirectoryReader.open(buildIndex(agent))
    private val searcher = IndexSearcher(reader)

    val parser = QueryParser(ScoredDocument.EXPRESSION, analyzer)

    /**
     * We assume each agent has its separate index.
     */
    fun search(rquery: String,
               expectations: DialogExpectations = DialogExpectations(),
               emap: MutableMap<String, MutableList<SpanInfo>>? = null): List<ScoredDocument> {
        if (rquery.isEmpty()) return listOf()

        var searchQuery = QueryParser.escape(rquery)


        logger.info("search with expression: $searchQuery")
        val query = parser.parse("expression:$searchQuery")

        // first build the expectation boolean it should be or query.
        // always add "default" for context filtering.
        val contextQueryBuilder = BooleanQuery.Builder()

        contextQueryBuilder.add(TermQuery(Term(ScoredDocument.CONTEXT, "default")), BooleanClause.Occur.SHOULD)
        if (expectations.activeFrames.isNotEmpty()) {
            for (expectation in expectations.getFrameContext()) {
                contextQueryBuilder.add(
                    TermQuery(Term(ScoredDocument.CONTEXT, expectation)),
                    BooleanClause.Occur.SHOULD
                )
                logger.info("search with context: $expectation")
            }
        }

        val queryBuilder = BooleanQuery.Builder()
        queryBuilder.add(query, BooleanClause.Occur.MUST)
        queryBuilder.add(contextQueryBuilder.build(), BooleanClause.Occur.MUST)

        val results = searcher.search(queryBuilder.build(), k).scoreDocs.toList()

        logger.info("got ${results.size} raw results for ${query}")

        if (results.isEmpty()) return emptyList()

        val res = ArrayList<ScoredDocument>()
        val keyCounts = mutableMapOf<String, Int>()
        val topScore = results[0].score
        var lastScore = topScore
        for (result in results) {
            val doc = ScoredDocument(result.score / topScore, reader.document(result.doc))
            val count = keyCounts.getOrDefault(doc.ownerFrame, 0)
            keyCounts[doc.ownerFrame] = count + 1
            if (keyCounts[doc.ownerFrame]!! <= maxFromSame || doc.score == lastScore) {
                logger.info(doc.toString())
                res.add(doc)
            }
            lastScore = doc.score
        }

        logger.info("got ${res.size} results for ${query}")
        return res
    }

    companion object {
        private val AngleSlotRegex = Pattern.compile("""<(.+?)>""").toRegex()
        val logger: Logger = LoggerFactory.getLogger(ExpressionSearcher::class.java)
        private val LessGreaterThanRegex = Regex("(?<=[<>])|(?=[<>])")

        @JvmStatic
        fun buildIndex(agent: DUMeta): Directory {
            logger.info("Dispatcher.memeoryBased = ${Dispatcher.memoryBased}")
            // Use ram directory, not as safe, but should be faster as we reduced io.
            return if (Dispatcher.memoryBased) {
                RAMDirectory().apply {
                    buildIndexRaw(agent, this)
                }
            } else {
                val dirAsFile = File("./index/${agent.getOrg()}_${agent.getLabel()}_${agent.getLang()}_${agent.getBranch()}")
                val path = Paths.get(dirAsFile.absolutePath)
                logger.info("Dispatcher.indexing: dirExist = ${dirAsFile.exists()}")
                // Make sure we delete the past index for springboot so that at least we use the newer version
                // as we are rely on org/agent/version for uniqueness, which might fail.
                val needIndex = !dirAsFile.exists()
                MMapDirectory(path).apply{
                     if (needIndex) {
                         buildIndexRaw(agent, this)
                     }
                }
            }
        }

        fun buildIndexRaw(agent: DUMeta, dir: Directory) {
            val expressions = agent.expressionsByFrame.values.flatten()
            logger.info("[ExpressionSearch] build index for ${agent.getLabel()}")
            val indexBuilder = IndexBuilder(dir, agent.getLang())
            expressions.map { indexBuilder.index(it.toDoc()) }
            indexBuilder.close()
        }
    }
}

