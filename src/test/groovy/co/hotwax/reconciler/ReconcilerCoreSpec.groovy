package co.hotwax.reconciler

import spock.lang.Specification

class ReconcilerCoreSpec extends Specification {
    def "CSV parser preserves quoted commas embedded quotes and multiline values"() {
        given:
        String csv = '''metadata line
enumId,description,enumTypeId
TEST_ONE,"Hello, world",TYPE_A
TEST_TWO,"Line one
Line ""two""",TYPE_A
'''

        when:
        List<Map<String, Object>> records = RemoteInstanceClient.parseMoquiCsv(csv)

        then:
        records.size() == 2
        records[0].description == "Hello, world"
        records[1].description == 'Line one\nLine "two"'
    }

    def "authentication header does not duplicate Bearer prefix"() {
        expect:
        RemoteInstanceClient.authenticationHeaders("Bearer abc").Authorization == "Bearer abc"
        RemoteInstanceClient.authenticationHeaders("abc").Authorization == "Bearer abc"
        RemoteInstanceClient.authenticationHeaders("JSESSIONID=value").Cookie == "JSESSIONID=value"
    }

    def "git diff parser reads multiline single quoted entity tags"() {
        given:
        String diff = '''diff --git a/data/Config.xml b/data/Config.xml
index 123..456 100644
--- a/data/Config.xml
+++ b/data/Config.xml
@@ -1,1 +1,4 @@
+<moqui.basic.Enumeration
+    enumId='NEW_ENUM'
+    enumTypeId='TEST_TYPE'
+    description='New value'/>
'''

        when:
        GitDiffScanner.DiffScanResult result = GitDiffScanner.parseDiffText(
                diff, ["moqui.basic.Enumeration"])

        then:
        result.success
        result.additions["moqui.basic.Enumeration"]["NEW_ENUM"].description == "New value"
    }

    def "git diff parser fails closed on incomplete added XML tag"() {
        given:
        String diff = '''diff --git a/data/Config.xml b/data/Config.xml
--- a/data/Config.xml
+++ b/data/Config.xml
@@ -1,2 +1,2 @@
+<moqui.basic.Enumeration enumId="BROKEN"
 context
'''

        when:
        GitDiffScanner.DiffScanResult result = GitDiffScanner.parseDiffText(
                diff, ["moqui.basic.Enumeration"])

        then:
        !result.success
        result.errors
    }

    def "docker resolver reports changed and newly introduced components"() {
        given:
        String diff = '''-    <component name="oms" group="hotwax" version="1.0.0"/>
+    <component name="oms" group="hotwax" version="1.1.0"/>
+    <component name="new-component" group="hotwax" version="2.0.0"/>
'''

        when:
        def shifts = DockerConfigReleaseResolver.parseDiff(new BufferedReader(new StringReader(diff)))

        then:
        shifts.find { it.name == "oms" }.fromTag == "v1.0.0"
        shifts.find { it.name == "oms" }.toTag == "v1.1.0"
        shifts.find { it.name == "new-component" }.fromTag == null
    }

    def "git scanner recognizes root service directory and records contract changes"() {
        given:
        String diff = '''diff --git a/service/co/hotwax/example/ExampleServices.xml b/service/co/hotwax/example/ExampleServices.xml
--- a/service/co/hotwax/example/ExampleServices.xml
+++ b/service/co/hotwax/example/ExampleServices.xml
@@ -1,5 +1,5 @@
 <service verb="update" noun="Thing">
 <in-parameters>
-  <parameter name="requiredId"/>
+  <parameter name="requiredId" required="true"/>
 </in-parameters>
'''
        def context = ServiceContractScanner.createContext()

        when:
        GitDiffScanner.DiffScanResult result = GitDiffScanner.parseDiffText(
                diff, ["moqui.basic.Enumeration"], "hotwax/example", "example", "v1->v2", [], context)
        ServiceContractScanner.finalizeContext(context)

        then:
        result.success
        context.breakingChanges.size() == 1
        context.breakingChanges[0].serviceName ==
                "co.hotwax.example.ExampleServices.update#Thing"
    }

    def "service scanner builds full names and pairs modified declarations"() {
        given:
        def context = ServiceContractScanner.createContext()
        String file = "service/co/hotwax/example/ExampleServices.xml"

        when:
        [
                ' <service verb="update" noun="Thing">',
                ' <in-parameters>',
                '-    <parameter name="requiredId"/>',
                '+    <parameter name="requiredId" required="true"/>',
                ' </in-parameters>'
        ].each {
            ServiceContractScanner.processLine(it, file, "hotwax/example", "example", "v1->v2", context)
        }
        ServiceContractScanner.finalizeContext(context)

        then:
        context.breakingChanges.size() == 1
        context.breakingChanges[0].serviceName ==
                "co.hotwax.example.ExampleServices.update#Thing"
        context.breakingChanges[0].changeType == "PARAM_BECAME_REQUIRED"
    }

    def "service declaration edit is modified rather than deleted and new"() {
        given:
        def context = ServiceContractScanner.createContext()
        String file = "service/co/hotwax/example/ExampleServices.xml"

        when:
        ServiceContractScanner.processLine(
                '-<service verb="update" noun="Thing" transaction-timeout="60">',
                file, "hotwax/example", "example", "v1->v2", context)
        ServiceContractScanner.processLine(
                '+<service verb="update" noun="Thing" transaction-timeout="120">',
                file, "hotwax/example", "example", "v1->v2", context)
        ServiceContractScanner.finalizeContext(context)

        then:
        context.modifiedServices.size() == 1
        context.newServices.empty
        context.deletedServices.empty
    }

    def "scheduled job activity excludes paused ad hoc and expired jobs"() {
        given:
        Date now = new Date()

        expect:
        ServiceIntegrityValidator.isActiveScheduledJob([cronExpression: "0 0 * * * ?", paused: "N"], now)
        !ServiceIntegrityValidator.isActiveScheduledJob([cronExpression: null, paused: "N"], now)
        !ServiceIntegrityValidator.isActiveScheduledJob([cronExpression: "0 0 * * * ?", paused: "Y"], now)
        !ServiceIntegrityValidator.isActiveScheduledJob(
                [cronExpression: "0 0 * * * ?", paused: "N", thruDate: new Date(now.time - 1000)], now)
    }

    def "composite primary key encoding is stable and collision resistant"() {
        expect:
        ReconcileEngine.primaryKey(["a", "b"], [a: "x|y", b: "z"]) ==
                ReconcileEngine.primaryKey(["a", "b"], [b: "z", a: "x|y"])
        ReconcileEngine.primaryKey(["a", "b"], [a: "x", b: "y|z"]) !=
                ReconcileEngine.primaryKey(["a", "b"], [a: "x|y", b: "z"])
        ReconcileEngine.primaryKey(["a", "b"], [a: "x"]) == null
    }
}
