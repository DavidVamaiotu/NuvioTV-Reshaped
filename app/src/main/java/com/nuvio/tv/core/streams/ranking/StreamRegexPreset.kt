package com.nuvio.tv.core.streams.ranking

/** The same preset definitions are used by single and ranked regex editors. */
enum class StreamRegexPreset(val pattern: String) {
    ANY_1080P("(2160p|4k|1080p)"),
    QUALITY_4K_REMUX("(2160p|4k|remux)"),
    FULL_HD_STANDARD("(1080p|full\\s*hd)"),
    HD_SMALLER("(720p|webrip|web-dl)"),
    WEB_SOURCES("(web[-\\s]?dl|webrip)"),
    BLURAY_QUALITY("(bluray|b[dr]rip|remux)"),
    HEVC_X265("(hevc|x265|h\\.265)"),
    AVC_X264("(x264|h\\.264|avc)"),
    HDR_DV("(hdr|hdr10\\+?|dv|dolby\\s*vision)"),
    DOLBY_ATMOS_DTS("(atmos|truehd|dts[-\\s]?hd|dtsx?)"),
    ENGLISH("(\\beng\\b|english)"),
    NO_CAM_TS("^(?!.*\\b(cam|hdcam|ts|telesync)\\b).*$"),
    NO_REMUX_HDR("(?is)^(?!.*\\b(hdr|hdr10|dv|dolby|vision|hevc|remux|2160p)\\b).+$")
}
