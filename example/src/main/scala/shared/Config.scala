package shared

// TODO make it json

val START_URL = "http://localhost:8080/page/1"
val LINK_REGEX = """<a\s+(?:[^>]*?\s+)?href="([^"]*)"|<a\s+(?:[^>]*?\s+)?href='([^']*)'""".r
val MAIN_DOMAIN = "localhost:8080"
val DEBUG = false
