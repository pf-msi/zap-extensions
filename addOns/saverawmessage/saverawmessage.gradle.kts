import org.zaproxy.gradle.addon.AddOnStatus

description = "Allows to save content of HTTP messages as binary"

zapAddOn {
    addOnName.set("Save Raw Message")
    addOnStatus.set(AddOnStatus.RELEASE)
    zapVersion.set("2.11.0")

    manifest {
        author.set("ZAP Dev Team")
        url.set("https://www.zaproxy.org/docs/desktop/addons/save-raw-message/")

        helpSet {
            baseName.set("help%LC%.helpset")
            localeToken.set("%LC%")
        }
    }
}
