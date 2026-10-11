package jenkins.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class XmlMergeTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    private static final String BASE = """
            <?xml version='1.1' encoding='UTF-8'?>
            <hudson>
              <systemMessage>hello</systemMessage>
              <quietPeriod>5</quietPeriod>
              <scmCheckoutRetryCount>0</scmCheckoutRetryCount>
            </hudson>""";

    @Test
    void differentSettingsBothSurvive() {
        String mine = BASE.replace("<quietPeriod>5<", "<quietPeriod>9<");
        String theirs = BASE.replace("<scmCheckoutRetryCount>0<", "<scmCheckoutRetryCount>4<");
        XmlMerge.Result r = XmlMerge.merge(b(BASE), b(mine), b(theirs));
        assertNotNull(r);
        assertEquals(0, r.conflicts());
        assertEquals("""
                <?xml version='1.1' encoding='UTF-8'?>
                <hudson>
                  <systemMessage>hello</systemMessage>
                  <quietPeriod>9</quietPeriod>
                  <scmCheckoutRetryCount>4</scmCheckoutRetryCount>
                </hudson>""", s(r.merged()));
    }

    @Test
    void sameSettingChangedOnBothSidesKeepsMineAndCounts() {
        XmlMerge.Result r = XmlMerge.merge(b(BASE), b(BASE.replace(">hello<", ">mine<")), b(BASE.replace(">hello<", ">theirs<")));
        assertNotNull(r);
        assertEquals(1, r.conflicts());
        assertEquals(BASE.replace(">hello<", ">mine<"), s(r.merged()));
    }

    @Test
    void elementsAddedAndRemoved() {
        String mine = BASE.replace("  <quietPeriod>5</quietPeriod>\n", ""); // reset to the default: element gone
        String theirs = BASE.replace("</hudson>", "  <label>linux</label>\n</hudson>");
        XmlMerge.Result r = XmlMerge.merge(b(BASE), b(mine), b(theirs));
        assertNotNull(r);
        assertEquals(0, r.conflicts());
        assertEquals("""
                <?xml version='1.1' encoding='UTF-8'?>
                <hudson>
                  <systemMessage>hello</systemMessage>
                  <scmCheckoutRetryCount>0</scmCheckoutRetryCount>
                  <label>linux</label>
                </hudson>""", s(r.merged()));
    }

    @Test
    void multiLineTextIsKeptVerbatim() {
        String base = "<?xml version='1.1' encoding='UTF-8'?>\n<project>\n  <description>a\n  b\n    c</description>\n  <disabled>false</disabled>\n</project>";
        String theirs = base.replace("<disabled>false<", "<disabled>true<");
        XmlMerge.Result r = XmlMerge.merge(b(base), b(base.replace("    c<", "    d<")), b(theirs));
        assertNotNull(r);
        assertEquals("<?xml version='1.1' encoding='UTF-8'?>\n<project>\n  <description>a\n  b\n    d</description>\n  <disabled>true</disabled>\n</project>", s(r.merged()));
    }

    @Test
    void listsMergeAsWholes() {
        String base = "<x>\n  <item>1</item>\n  <item>2</item>\n</x>";
        XmlMerge.Result r = XmlMerge.merge(b(base), b(base.replace(">1<", ">one<")), b(base.replace(">2<", ">two<")));
        assertNotNull(r);
        assertEquals(1, r.conflicts(), "both changed the list of items");
    }

    @Test
    void differentPartsOfOneNestedElementBothSurvive() {
        // a user record: one controller changes the password, another adds an API token
        String base = """
                <?xml version='1.1' encoding='UTF-8'?>
                <user>
                  <id>admin</id>
                  <properties>
                    <hudson.security.HudsonPrivateSecurityRealm_-Details><passwordHash>old</passwordHash></hudson.security.HudsonPrivateSecurityRealm_-Details>
                    <jenkins.security.ApiTokenProperty><tokenStore/></jenkins.security.ApiTokenProperty>
                  </properties>
                </user>""";
        String mine = base.replace("<passwordHash>old<", "<passwordHash>new<");
        String theirs = base.replace("<tokenStore/>", "<tokenStore><name>harness</name></tokenStore>");
        XmlMerge.Result r = XmlMerge.merge(b(base), b(mine), b(theirs));
        assertNotNull(r);
        assertEquals(0, r.conflicts());
        String merged = s(r.merged());
        assertTrue(merged.contains("<passwordHash>new</passwordHash>"), merged);
        assertTrue(merged.contains("<tokenStore><name>harness</name></tokenStore>"), merged);
        assertTrue(merged.contains("<id>admin</id>"), merged);
    }

    @Test
    void sameNestedPartChangedOnBothSidesKeepsMine() {
        String base = "<user>\n  <properties>\n    <a>1</a>\n    <b>1</b>\n  </properties>\n</user>";
        XmlMerge.Result r = XmlMerge.merge(b(base), b(base.replace("<a>1<", "<a>mine<")), b(base.replace("<a>1<", "<a>theirs<")));
        assertNotNull(r);
        assertEquals(1, r.conflicts());
        assertTrue(s(r.merged()).contains("<a>mine</a>"));
    }

    @Test
    void notMergeable() {
        assertNull(XmlMerge.merge(b(BASE), b("not xml"), b(BASE)));
        assertNull(XmlMerge.merge(b(BASE), b(BASE), b("<other/>")), "different root");
        assertNull(XmlMerge.merge(b("<x>text<y/></x>"), b("<x>text<y/></x>"), b("<x>text<y/></x>")), "mixed content");
    }

    @Test
    void doctypeRefused() {
        assertNull(XmlMerge.merge(b("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><x>&e;</x>"), b("<x/>"), b("<x/>")));
    }
}
