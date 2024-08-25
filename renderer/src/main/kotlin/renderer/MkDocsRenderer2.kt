package opensavvy.dokka.material.mkdocs.renderer

import opensavvy.dokka.material.mkdocs.GfmCommand.Companion.templateCommand
import opensavvy.dokka.material.mkdocs.MaterialForMkDocsPlugin
import opensavvy.dokka.material.mkdocs.ResolveLinkGfmCommand
import org.jetbrains.dokka.DokkaException
import org.jetbrains.dokka.base.renderers.DefaultRenderer
import org.jetbrains.dokka.model.DisplaySourceSet
import org.jetbrains.dokka.pages.*
import org.jetbrains.dokka.plugability.DokkaContext
import org.jetbrains.dokka.plugability.plugin
import org.jetbrains.dokka.plugability.query
import org.jetbrains.dokka.transformers.pages.PageTransformer

open class MkDocsRenderer2(
	context: DokkaContext,
) : DefaultRenderer<StringBuilder>(context) {
	override val preprocessors: List<PageTransformer> = context.plugin<MaterialForMkDocsPlugin>().query { mkdocsPreprocessor }

	override fun buildError(node: ContentNode) {
		context.logger.warn("MkDocs renderer has encountered problem. The unmatched node is $node")
	}

	override fun StringBuilder.buildText(textNode: ContentText) {
		appendLine("TEXT NODE $textNode\n")
	}

	override fun StringBuilder.buildTable(node: ContentTable, pageContext: ContentPage, sourceSetRestriction: Set<DisplaySourceSet>?) {
		appendLine("TABLE NODE $node\n")
	}

	override fun StringBuilder.buildResource(node: ContentEmbeddedResource, pageContext: ContentPage) {
		appendLine("RESOURCE NODE $node\n")
	}

	override fun StringBuilder.buildNavigation(page: PageNode) {
		var first = true

		locationProvider.ancestors(page).asReversed()
			.filter { it.name.isNotBlank() }
			.forEach { node ->
				if (first) {
					first = false
				} else {
					append(" • ")
				}

				if (node.isNavigable) buildLink(node, page)
				else append(node.name)
			}

		appendLine()
		appendLine()
	}

	override fun StringBuilder.buildList(node: ContentList, pageContext: ContentPage, sourceSetRestriction: Set<DisplaySourceSet>?) {
		appendLine("LIST NODES $node\n")
	}

	// region Links

	private fun StringBuilder.buildLink(to: PageNode, from: PageNode) =
		buildLink(locationProvider.resolve(to, from, skipExtension = true)!! + ".html") {
			append(to.name)
		}

	override fun StringBuilder.buildLink(address: String, content: StringBuilder.() -> Unit) {
		append("<a href=\"$address\">\n")
		content()
		append("\n</a>")
	}

	override fun StringBuilder.buildDRILink(
		node: ContentDRILink,
		pageContext: ContentPage,
		sourceSetRestriction: Set<DisplaySourceSet>?,
	) {
		val location = locationProvider.resolve(node.address, node.sourceSets, pageContext)
			?.removeSuffix(".md")
			?.plus(".html")
		if (location == null) {
			val isPartial = context.configuration.delayTemplateSubstitution

			if (isPartial) {
				templateCommand(ResolveLinkGfmCommand(node.address)) {
					buildText(node.children, pageContext, sourceSetRestriction)
				}
			} else {
				buildText(node.children, pageContext, sourceSetRestriction)
			}
		} else {
			buildLink(location) {
				buildText(node.children, pageContext, sourceSetRestriction)
			}
		}
	}

	// endregion

	override fun StringBuilder.buildLineBreak() {
		appendLine("<br/>")
	}

	override fun StringBuilder.buildHeader(level: Int, node: ContentHeader, content: StringBuilder.() -> Unit) {
		appendLine("HEADER $node\n")
	}

	// region Overall page rendering

	/**
	 * Overall layout of each page.
	 */
	override fun buildPageContent(context: StringBuilder, page: ContentPage) = with(context) {
		// Front matter
		appendLine("---")
		appendLine("tags:")
		(page as? WithDocumentables)
			?.documentables
			?.flatMapTo(HashSet()) { it.sourceSets }
			?.map { it.analysisPlatform.key }
			?.forEach {
				appendLine(" - $it")
			}
		page.content
			.children
			.flatMap {
				if (it is ContentGroup)
					it.children
				else listOf(it)
			}
			.filterIsInstance<ContentHeader>()
			.firstOrNull { it.level == 1 }
			?.children
			?.filterIsInstance<ContentText>()
			?.joinToString(" ") { it.text }
			?.also { appendLine("title: $it") }
		appendLine("---")

		// Navigation
		context.buildNavigation(page)

		// Contents
		page.content.build(context, page)
	}

	override suspend fun renderPage(page: PageNode) {
		val path by lazy {
			locationProvider.resolve(page, skipExtension = true)
				?: throw DokkaException("Cannot resolve path for ${page.name}")
		}

		return when (page) {
			is ContentPage -> outputWriter.write(path, buildPage(page) { c, p -> buildPageContent(c, p) }, ".md")
			is RendererSpecificPage -> when (val strategy = page.strategy) {
				is RenderingStrategy.Copy -> outputWriter.writeResources(strategy.from, path)
				is RenderingStrategy.Write -> outputWriter.write(path, strategy.text, "")
				is RenderingStrategy.Callback -> outputWriter.write(path, strategy.instructions(this, page), ".md")
				is RenderingStrategy.DriLocationResolvableWrite -> outputWriter.write(
					path,
					strategy.contentToResolve { dri, sourcesets ->
						locationProvider.resolve(dri, sourcesets)
					},
					""
				)

				is RenderingStrategy.PageLocationResolvableWrite -> outputWriter.write(
					path,
					strategy.contentToResolve { pageToLocate, context ->
						locationProvider.resolve(pageToLocate, context)
					},
					""
				)

				RenderingStrategy.DoNothing -> Unit
			}

			else -> throw AssertionError(
				"Page ${page.name} cannot be rendered by renderer as it is not renderer specific nor contains content"
			)
		}
	}

	override fun buildPage(page: ContentPage, content: (StringBuilder, ContentPage) -> Unit): String =
		buildString {
			content(this, page)
		}.trim().replace("\n[\n]+".toRegex(), "\n\n")

	// endregion
}

private val PageNode.isNavigable: Boolean
	get() = this !is RendererSpecificPage || strategy != RenderingStrategy.DoNothing