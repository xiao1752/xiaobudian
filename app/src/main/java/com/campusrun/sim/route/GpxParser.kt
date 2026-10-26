package com.campusrun.sim.route

import android.util.Xml
import com.campusrun.sim.model.RoutePoint
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/** Minimal GPX track-point parser: reads every <trkpt lat=".." lon="..">. */
object GpxParser {
    fun parse(input: InputStream): List<RoutePoint> {
        val points = mutableListOf<RoutePoint>()
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "trkpt") {
                val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                if (lat != null && lon != null) points.add(RoutePoint(lat, lon))
            }
            event = parser.next()
        }
        return points
    }
}
