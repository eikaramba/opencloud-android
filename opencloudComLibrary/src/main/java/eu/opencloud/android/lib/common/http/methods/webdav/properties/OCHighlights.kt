package eu.opencloud.android.lib.common.http.methods.webdav.properties

import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.PropertyFactory
import at.bitfire.dav4jvm.XmlUtils
import org.xmlpull.v1.XmlPullParser

/**
 * Text snippet of a content search hit, with the matched terms wrapped in `<mark>` tags.
 * Only sent by the server for content matches in a search REPORT.
 */
data class OCHighlights(val highlights: String) : Property {
    class Factory : PropertyFactory {
        override fun getName() = NAME

        override fun create(parser: XmlPullParser): OCHighlights? =
            XmlUtils.readText(parser)?.let { OCHighlights(it) }
    }

    companion object {
        @JvmField
        val NAME = Property.Name(XmlUtils.NS_OWNCLOUD, "highlights")
    }
}
