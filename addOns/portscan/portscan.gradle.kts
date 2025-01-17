import org.zaproxy.gradle.addon.AddOnStatus

description = "Allows to port scan a target server"

zapAddOn {
    addOnName.set("Port Scanner")
    addOnStatus.set(AddOnStatus.BETA)
    zapVersion.set("2.11.0")

    manifest {
        author.set("ZAP Dev Team")
        url.set("https://www.zaproxy.org/docs/desktop/addons/port-scan/")
    }
}
