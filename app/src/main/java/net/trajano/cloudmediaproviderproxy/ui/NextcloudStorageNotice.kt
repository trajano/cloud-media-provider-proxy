package net.trajano.cloudmediaproviderproxy.ui

internal fun isNextcloudDocumentsAuthority(authority: String?): Boolean =
    authority == "org.nextcloud.documents" || authority?.startsWith("org.nextcloud.documents.") == true
