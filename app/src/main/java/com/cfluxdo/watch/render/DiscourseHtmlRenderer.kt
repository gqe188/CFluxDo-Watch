package com.cfluxdo.watch.render

import android.text.Spanned
import androidx.core.text.HtmlCompat
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Lightweight native Discourse HTML -> Watch blocks parser.
 * Completely eliminates WebView for reading, keeping RAM minimal and scroll silky-smooth at 60fps.
 */
sealed class PostBlock {
    data class RichText(val spanned: Spanned) : PostBlock()
    data class Quote(val author: String, val text: String) : PostBlock()
    data class Code(val text: String) : PostBlock()
    data class Spoiler(val summary: String, val body: String) : PostBlock()
    data class Image(val url: String, val alt: String) : PostBlock()
}

object DiscourseHtmlRenderer {
    fun parse(cookedHtml: String): List<PostBlock> {
        if (cookedHtml.isBlank()) return emptyList()
        val doc = Jsoup.parseBodyFragment(cookedHtml)
        val body = doc.body()

        // Replace Discourse emoji <img> with alt text so they don't spawn unnecessary image loads
        for (img in body.select("img.emoji")) {
            val alt = img.attr("alt").ifBlank { img.attr("title") }
            img.replaceWith(org.jsoup.nodes.TextNode(alt))
        }
        // Remove lightbox metadata clutter (dimensions/file size spans)
        body.select(".meta, .svg-icon, .lightbox-wrapper .meta").remove()

        val blocks = mutableListOf<PostBlock>()
        val htmlBuf = StringBuilder()

        fun flushHtml() {
            val raw = htmlBuf.toString().trim()
            htmlBuf.setLength(0)
            if (raw.isEmpty()) return
            val spanned = HtmlCompat.fromHtml(raw, HtmlCompat.FROM_HTML_MODE_COMPACT)
            val trimmed = trimSpanned(spanned)
            if (trimmed.isNotEmpty()) {
                blocks.add(PostBlock.RichText(trimmed))
            }
        }

        for (child in body.children()) {
            val tag = child.tagName().lowercase()
            when {
                tag == "aside" && child.hasClass("quote") -> {
                    flushHtml()
                    val title = child.selectFirst(".title")?.text()?.trim().orEmpty()
                    val quoteText = child.selectFirst("blockquote")?.text()?.trim()
                        ?: child.text().trim()
                    if (quoteText.isNotEmpty()) {
                        blocks.add(PostBlock.Quote(title, quoteText))
                    }
                }
                tag == "blockquote" -> {
                    flushHtml()
                    val qt = child.text().trim()
                    if (qt.isNotEmpty()) {
                        blocks.add(PostBlock.Quote("", qt))
                    }
                }
                tag == "pre" -> {
                    flushHtml()
                    val code = child.text().trimEnd()
                    if (code.isNotEmpty()) {
                        blocks.add(PostBlock.Code(code))
                    }
                }
                tag == "details" -> {
                    flushHtml()
                    val summaryEl = child.selectFirst("summary")
                    val summary = summaryEl?.text()?.trim()?.ifEmpty { "展开折叠内容" } ?: "展开折叠内容"
                    summaryEl?.remove()
                    val content = child.text().trim()
                    if (content.isNotEmpty()) {
                        blocks.add(PostBlock.Spoiler(summary, content))
                    }
                }
                tag == "div" && child.hasClass("lightbox-wrapper") -> {
                    flushHtml()
                    extractImage(child)?.let { blocks.add(it) }
                }
                tag == "p" && child.select("img").isNotEmpty() -> {
                    // Extract images from paragraph, preserve remaining text
                    val imgs = child.select("img")
                    val extracted = mutableListOf<PostBlock.Image>()
                    for (img in imgs) {
                        val src = img.attr("src").ifBlank { img.attr("data-src") }
                        if (src.isNotBlank() && !img.hasClass("avatar")) {
                            extracted.add(PostBlock.Image(src, img.attr("alt")))
                        }
                        img.remove()
                    }
                    val remainingHtml = child.outerHtml()
                    if (Jsoup.parseBodyFragment(remainingHtml).text().isNotBlank()) {
                        htmlBuf.append(remainingHtml)
                    }
                    flushHtml()
                    blocks.addAll(extracted)
                }
                tag == "img" -> {
                    flushHtml()
                    val src = child.attr("src").ifBlank { child.attr("data-src") }
                    if (src.isNotBlank() && !child.hasClass("avatar")) {
                        blocks.add(PostBlock.Image(src, child.attr("alt")))
                    }
                }
                tag == "aside" && child.hasClass("onebox") -> {
                    flushHtml()
                    val linkTitle = child.selectFirst("h3, h4, a")?.text()?.trim().orEmpty()
                    val desc = child.selectFirst("p")?.text()?.trim().orEmpty()
                    val combined = listOf(linkTitle, desc).filter { it.isNotEmpty() }.joinToString("\n")
                    if (combined.isNotEmpty()) {
                        blocks.add(PostBlock.Quote("链接卡片", combined))
                    }
                }
                else -> {
                    htmlBuf.append(child.outerHtml())
                }
            }
        }
        flushHtml()

        if (blocks.isEmpty()) {
            val plain = body.text().trim()
            if (plain.isNotEmpty()) {
                blocks.add(PostBlock.RichText(HtmlCompat.fromHtml(plain, HtmlCompat.FROM_HTML_MODE_COMPACT)))
            }
        }
        return blocks
    }

    private fun extractImage(wrapper: Element): PostBlock.Image? {
        val linkHref = wrapper.selectFirst("a.lightbox")?.attr("href")
        val img = wrapper.selectFirst("img")
        val url = img?.attr("src")?.takeIf { it.isNotBlank() }
            ?: linkHref?.takeIf { it.isNotBlank() }
            ?: return null
        return PostBlock.Image(url, img?.attr("alt").orEmpty())
    }

    private fun trimSpanned(spanned: Spanned): Spanned {
        var start = 0
        var end = spanned.length
        while (start < end && spanned[start].isWhitespace()) start++
        while (end > start && spanned[end - 1].isWhitespace()) end--
        return spanned.subSequence(start, end) as Spanned
    }
}
