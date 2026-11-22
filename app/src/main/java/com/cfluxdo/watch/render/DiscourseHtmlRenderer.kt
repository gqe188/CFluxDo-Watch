package com.cfluxdo.watch.render

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.core.text.HtmlCompat
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Represents an inline Discourse emoji / custom sticker image embedded within text.
 */
data class InlineEmoji(
    val url: String,
    val alt: String,
    val start: Int,
    val end: Int,
    val isLarge: Boolean
)

/**
 * Lightweight native Discourse HTML -> Watch blocks parser.
 * Completely eliminates WebView for reading while natively rendering Discourse emojis & stickers.
 */
sealed class PostBlock {
    data class RichText(
        val spanned: Spanned,
        val emojis: List<InlineEmoji> = emptyList()
    ) : PostBlock()

    data class Quote(
        val author: String,
        val text: String,
        val emojis: List<InlineEmoji> = emptyList()
    ) : PostBlock()

    data class Code(val text: String) : PostBlock()
    data class Spoiler(val summary: String, val body: String) : PostBlock()
    data class Image(
        val url: String,
        val alt: String,
        val isSticker: Boolean = false
    ) : PostBlock()
}

/**
 * Pure Jsoup emoji/sticker extraction helpers (JVM-testable without Android framework).
 */
internal object DiscourseEmojiHelper {
    private const val MARKER_START = '\uE000'
    private const val MARKER_END = '\uE001'
    private val markerRegex = Regex("$MARKER_START(\\d+)$MARKER_END")

    data class EmojiToken(
        val url: String,
        val alt: String,
        val isLarge: Boolean
    )

    data class ResolvedText(
        val text: String,
        val emojis: List<InlineEmoji>
    )

    fun isEmojiImage(img: Element): Boolean {
        if (img.hasClass("emoji") || img.hasClass("emoji-custom") || img.hasClass("only-emoji")) {
            return true
        }
        val src = img.attr("src").ifBlank { img.attr("data-src") }.lowercase()
        return src.contains("/images/emoji/") || src.contains("/uploads/default/original/") && img.attr("alt").startsWith(":") && img.attr("alt").endsWith(":")
    }

    fun isSmallStickerImage(img: Element): Boolean {
        if (img.hasClass("avatar") || isEmojiImage(img)) return false
        val w = img.attr("width").toIntOrNull()
        val h = img.attr("height").toIntOrNull()
        if (w != null && h != null && w in 1..150 && h in 1..150) {
            return true
        }
        val alt = img.attr("alt").lowercase()
        return alt.contains("表情") || alt.contains("sticker") || alt.contains("emoji")
    }

    /**
     * Replaces all Discourse emoji <img> elements inside [root] with sentinel tokens and returns
     * the indexed [EmojiToken] list.
     */
    fun tokenizeEmojis(root: Element): List<EmojiToken> {
        val tokens = mutableListOf<EmojiToken>()
        for (img in root.select("img").toList()) {
            if (!isEmojiImage(img)) continue
            val src = img.attr("src").ifBlank { img.attr("data-src") }.trim()
            val alt = img.attr("alt").ifBlank { img.attr("title") }.ifBlank { "😀" }.trim()
            if (src.isBlank()) {
                img.replaceWith(TextNode(alt))
                continue
            }
            val isLarge = img.hasClass("only-emoji")
            val idx = tokens.size
            tokens.add(EmojiToken(url = src, alt = alt, isLarge = isLarge))
            // Keep a thin space separation when multiple emojis sit side-by-side
            img.replaceWith(TextNode("$MARKER_START$idx$MARKER_END"))
        }
        return tokens
    }

    fun resolvePlainText(raw: String, tokens: List<EmojiToken>): ResolvedText {
        if (tokens.isEmpty() || !raw.contains(MARKER_START)) {
            return ResolvedText(raw, emptyList())
        }
        val sb = StringBuilder(raw.length)
        val emojis = mutableListOf<InlineEmoji>()
        var cursor = 0
        for (m in markerRegex.findAll(raw)) {
            sb.append(raw, cursor, m.range.first)
            val idx = m.groupValues[1].toIntOrNull()
            val tok = if (idx != null && idx in tokens.indices) tokens[idx] else null
            if (tok != null) {
                val start = sb.length
                sb.append(tok.alt)
                val end = sb.length
                emojis.add(InlineEmoji(tok.url, tok.alt, start, end, tok.isLarge))
            }
            cursor = m.range.last + 1
        }
        if (cursor < raw.length) {
            sb.append(raw, cursor, raw.length)
        }
        return ResolvedText(sb.toString(), emojis)
    }

    fun resolveSpannable(spanned: Spanned, tokens: List<EmojiToken>): Pair<Spanned, List<InlineEmoji>> {
        if (tokens.isEmpty() || !spanned.contains(MARKER_START)) {
            return spanned to emptyList()
        }
        val ssb = SpannableStringBuilder(spanned)
        val emojis = mutableListOf<InlineEmoji>()
        var searchFrom = 0
        while (searchFrom < ssb.length) {
            val match = markerRegex.find(ssb, searchFrom) ?: break
            val idx = match.groupValues[1].toIntOrNull()
            val tok = if (idx != null && idx in tokens.indices) tokens[idx] else null
            val replacement = tok?.alt.orEmpty()
            val start = match.range.first
            ssb.replace(start, match.range.last + 1, replacement)
            val end = start + replacement.length
            if (tok != null && end > start) {
                emojis.add(InlineEmoji(tok.url, tok.alt, start, end, tok.isLarge))
            }
            searchFrom = end
        }
        return ssb to emojis
    }
}

object DiscourseHtmlRenderer {
    fun parse(cookedHtml: String): List<PostBlock> {
        if (cookedHtml.isBlank()) return emptyList()
        val doc = Jsoup.parseBodyFragment(cookedHtml)
        val body = doc.body()

        // Tokenize all Discourse emojis (standard + custom + only-emoji) so they render as real images
        val emojiTokens = DiscourseEmojiHelper.tokenizeEmojis(body)

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
                val (resolved, emojis) = DiscourseEmojiHelper.resolveSpannable(trimmed, emojiTokens)
                if (resolved.isNotEmpty()) {
                    blocks.add(PostBlock.RichText(resolved, emojis))
                }
            }
        }

        for (child in body.children()) {
            val tag = child.tagName().lowercase()
            when {
                tag == "aside" && child.hasClass("quote") -> {
                    flushHtml()
                    val title = child.selectFirst(".title")?.text()?.trim().orEmpty()
                    val rawQuote = child.selectFirst("blockquote")?.text()?.trim()
                        ?: child.text().trim()
                    val resolved = DiscourseEmojiHelper.resolvePlainText(rawQuote, emojiTokens)
                    if (resolved.text.isNotEmpty()) {
                        blocks.add(PostBlock.Quote(title, resolved.text, resolved.emojis))
                    }
                }
                tag == "blockquote" -> {
                    flushHtml()
                    val resolved = DiscourseEmojiHelper.resolvePlainText(child.text().trim(), emojiTokens)
                    if (resolved.text.isNotEmpty()) {
                        blocks.add(PostBlock.Quote("", resolved.text, resolved.emojis))
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
                    val resolved = DiscourseEmojiHelper.resolvePlainText(child.text().trim(), emojiTokens)
                    if (resolved.text.isNotEmpty()) {
                        blocks.add(PostBlock.Spoiler(summary, resolved.text))
                    }
                }
                tag == "div" && child.hasClass("lightbox-wrapper") -> {
                    flushHtml()
                    extractImage(child)?.let { blocks.add(it) }
                }
                tag == "p" && child.select("img").isNotEmpty() -> {
                    // Extract non-emoji content images from paragraph, preserve text & inline emoji tokens
                    val imgs = child.select("img")
                    val extracted = mutableListOf<PostBlock.Image>()
                    for (img in imgs) {
                        val src = img.attr("src").ifBlank { img.attr("data-src") }
                        if (src.isNotBlank() && !img.hasClass("avatar")) {
                            extracted.add(
                                PostBlock.Image(
                                    url = src,
                                    alt = img.attr("alt"),
                                    isSticker = DiscourseEmojiHelper.isSmallStickerImage(img)
                                )
                            )
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
                        blocks.add(
                            PostBlock.Image(
                                url = src,
                                alt = child.attr("alt"),
                                isSticker = DiscourseEmojiHelper.isSmallStickerImage(child)
                            )
                        )
                    }
                }
                tag == "aside" && child.hasClass("onebox") -> {
                    flushHtml()
                    val linkTitle = child.selectFirst("h3, h4, a")?.text()?.trim().orEmpty()
                    val desc = child.selectFirst("p")?.text()?.trim().orEmpty()
                    val combined = listOf(linkTitle, desc).filter { it.isNotEmpty() }.joinToString("\n")
                    val resolved = DiscourseEmojiHelper.resolvePlainText(combined, emojiTokens)
                    if (resolved.text.isNotEmpty()) {
                        blocks.add(PostBlock.Quote("链接卡片", resolved.text, resolved.emojis))
                    }
                }
                else -> {
                    htmlBuf.append(child.outerHtml())
                }
            }
        }
        flushHtml()

        if (blocks.isEmpty()) {
            val resolved = DiscourseEmojiHelper.resolvePlainText(body.text().trim(), emojiTokens)
            if (resolved.text.isNotEmpty()) {
                blocks.add(PostBlock.RichText(SpannableStringBuilder(resolved.text), resolved.emojis))
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
        return PostBlock.Image(
            url = url,
            alt = img?.attr("alt").orEmpty(),
            isSticker = img != null && DiscourseEmojiHelper.isSmallStickerImage(img)
        )
    }

    private fun trimSpanned(spanned: Spanned): Spanned {
        var start = 0
        var end = spanned.length
        while (start < end && spanned[start].isWhitespace()) start++
        while (end > start && spanned[end - 1].isWhitespace()) end--
        return spanned.subSequence(start, end) as Spanned
    }
}
