package android.provider

import android.net.Uri

object MediaStore {
    const val QUERY_ARG_MATCH_PENDING = "android:query-arg-match-pending"
    const val MATCH_INCLUDE = 1
    @JvmStatic fun setIncludePending(uri: Uri) = Uri.parse("$uri?includePending=1")
    object Images { object Media {
        @JvmField val EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/images/media")
        const val DISPLAY_NAME = "_display_name"
        const val MIME_TYPE = "mime_type"
        const val RELATIVE_PATH = "relative_path"
        const val IS_PENDING = "is_pending"
        const val DATE_TAKEN = "datetaken"
        const val OWNER_PACKAGE_NAME = "owner_package_name"
        const val _ID = "_id"
    } }
}
