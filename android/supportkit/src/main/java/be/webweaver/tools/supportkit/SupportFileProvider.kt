package be.webweaver.tools.supportkit

import androidx.core.content.FileProvider

/** Own subclass so the kit's provider never clashes with an app's own `FileProvider`. */
class SupportFileProvider : FileProvider()
