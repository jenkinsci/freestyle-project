/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package hudson.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import hudson.ExtensionList;
import hudson.diagnosis.OldDataMonitor;
import hudson.tasks.Builder;
import hudson.tasks.Shell;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import jenkins.model.Jenkins;
import org.htmlunit.FailingHttpStatusCodeException;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlForm;
import org.htmlunit.html.HtmlPage;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.SmokeTest;
import org.jvnet.hudson.test.recipes.WithTimeout;
import org.kohsuke.stapler.jelly.JellyFacet;

/**
 * @author Kohsuke Kawaguchi
 */
@Category(SmokeTest.class)
public class FreeStyleProjectTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    /**
     * Tests a trivial configuration round-trip.
     *
     * The goal is to catch a P1-level issue that prevents all the form submissions to fail.
     */
    @Test
    public void configSubmission() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        Shell shell = new Shell("echo hello");
        project.getBuildersList().add(shell);

        // emulate the user behavior
        WebClient webClient = j.createWebClient();
        HtmlPage page = webClient.getPage(project, "configure");

        HtmlForm form = page.getFormByName("config");
        j.submit(form);

        List<Builder> builders = project.getBuilders();
        assertEquals(1, builders.size());
        assertEquals(Shell.class, builders.get(0).getClass());
        assertEquals("echo hello", ((Shell) builders.get(0)).getCommand().trim());
        assertNotSame(builders.get(0), shell);
    }

    /**
     * Custom workspace and concurrent build had a bad interaction.
     */
    @Test
    @Issue("JENKINS-4206")
    public void customWorkspaceAllocation() throws Exception {
        FreeStyleProject f = j.createFreeStyleProject();
        f.setCustomWorkspace(tempFolder.newFolder().getPath());
        j.buildAndAssertSuccess(f);
    }

    /**
     * Custom workspace and variable expansion.
     */
    @Test
    @Issue("JENKINS-3997")
    public void customWorkspaceVariableExpansion() throws Exception {
        FreeStyleProject f = j.createFreeStyleProject();
        File d = new File(tempFolder.newFolder(), "${JOB_NAME}");
        f.setCustomWorkspace(d.getPath());
        FreeStyleBuild b = j.buildAndAssertSuccess(f);

        String path = b.getWorkspace().getRemote();
        System.out.println(path);
        assertFalse(path.contains("${JOB_NAME}"));
        assertEquals(b.getWorkspace().getName(), f.getName());
    }

    @Test
    @Issue("JENKINS-15817")
    public void minimalConfigXml() throws Exception {
        // Make sure it can be created without exceptions:
        FreeStyleProject project = (FreeStyleProject) j.jenkins.createProjectFromXML(
                "stuff", new ByteArrayInputStream("<project/>".getBytes(StandardCharsets.UTF_8)));
        System.out.println(project.getConfigFile().asString());
        // and round-tripped:
        Shell shell = new Shell("echo hello");
        project.getBuildersList().add(shell);
        WebClient webClient = j.createWebClient();
        HtmlPage page = webClient.getPage(project, "configure");
        HtmlForm form = page.getFormByName("config");
        j.submit(form);
        List<Builder> builders = project.getBuilders();
        assertEquals(1, builders.size());
        assertEquals(Shell.class, builders.get(0).getClass());
        assertEquals("echo hello", ((Shell) builders.get(0)).getCommand().trim());
        assertNotSame(builders.get(0), shell);
        System.out.println(project.getConfigFile().asString());
    }

    @Test
    @Issue("JENKINS-36629")
    @WithTimeout(300)
    public void buildStabilityReports() throws Exception {
        for (int i = 0; i <= 32; i++) {
            FreeStyleProject p = j.createFreeStyleProject("Pattern-%s".formatted(Integer.toBinaryString(i)));
            int expectedFails = 0;
            for (int j = 32; j >= 1; j = j / 2) {
                p.getBuildersList().clear();
                if ((i & j) == j) {
                    p.getBuildersList().add(new FailureBuilder());
                    if (j <= 16) {
                        expectedFails++;
                    }
                    this.j.buildAndAssertStatus(Result.FAILURE, p);
                } else {
                    this.j.buildAndAssertSuccess(p);
                }
            }
            HealthReport health = p.getBuildHealth();

            assertThat(
                    "Pattern %s score".formatted(Integer.toBinaryString(i)),
                    health.getScore(),
                    is(100 * (5 - expectedFails) / 5));
        }
    }

    @Issue("SECURITY-1923")
    @Test
    public void configDotXmlWithValidXmlAndBadField() throws Exception {
        final String CONFIGURATOR = "configure_user";

        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        MockAuthorizationStrategy mas = new MockAuthorizationStrategy();
        mas.grant(Item.CONFIGURE, Item.READ, Jenkins.READ).everywhere().to(CONFIGURATOR);
        j.jenkins.setAuthorizationStrategy(mas);

        FreeStyleProject project = j.createFreeStyleProject();

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.login(CONFIGURATOR);
        WebRequest req =
                new WebRequest(wc.createCrumbedUrl("%s/config.xml".formatted(project.getUrl())), HttpMethod.POST);
        req.setAdditionalHeader("Content-Type", "application/xml");
        req.setRequestBody(VALID_XML_BAD_FIELD_USER_XML);

        FailingHttpStatusCodeException e = assertThrows(FailingHttpStatusCodeException.class, () -> wc.getPage(req));
        // This really shouldn't return 500, but that's what it does now.
        assertThat(e.getStatusCode(), equalTo(500));

        OldDataMonitor odm = ExtensionList.lookupSingleton(OldDataMonitor.class);
        Map<Saveable, OldDataMonitor.VersionRange> data = odm.getData();

        assertThat(data.size(), equalTo(0));

        odm.doDiscard(null, null);

        User.AllUsers.scanAll();
        boolean createUser = false;
        User badUser = User.getById("foo", createUser);

        assertNull("Should not have created user.", badUser);
    }

    private static final String VALID_XML_BAD_FIELD_USER_XML = """
            <hudson.model.User>
              <id>foo</id>
              <fullName>Foo User</fullName>
              <badField/>
            </hudson.model.User>
            """;

    @Test
    @Issue("JENKINS-65288")
    public void submitPossibleWithoutJellyTrace() throws Exception {
        FreeStyleProject freeStyleProject = j.createFreeStyleProject();

        JenkinsRule.WebClient wc = j.createWebClient();
        HtmlPage htmlPage = wc.goTo(freeStyleProject.getUrl() + "configure");
        HtmlForm configForm = htmlPage.getFormByName("config");
        j.assertGoodStatus(j.submit(configForm));
    }

    /**
     * Ensure the form is still working when using {@link org.kohsuke.stapler.jelly.JellyFacet#TRACE}=true
     */
    @Test
    @Issue("JENKINS-65288")
    public void submitPossibleWithJellyTrace() throws Exception {
        boolean currentValue = JellyFacet.TRACE;
        try {
            JellyFacet.TRACE = true;

            FreeStyleProject freeStyleProject = j.createFreeStyleProject();

            JenkinsRule.WebClient wc = j.createWebClient();
            HtmlPage htmlPage = wc.goTo(freeStyleProject.getUrl() + "configure");
            HtmlForm configForm = htmlPage.getFormByName("config");
            j.assertGoodStatus(j.submit(configForm));
        } finally {
            JellyFacet.TRACE = currentValue;
        }
    }

    @Test
    @Issue("SECURITY-2424")
    public void cannotCreateJobWithTrailingDot_withoutOtherJob() throws Exception {
        assertThat(j.jenkins.getItems(), hasSize(0));
        Failure e = assertThrows(
                "Adding the job should have thrown an exception during checkGoodName",
                Failure.class,
                () -> j.jenkins.createProjectFromXML(
                        "jobA.", new ByteArrayInputStream("<project/>".getBytes(StandardCharsets.UTF_8))));
        assertEquals(Messages.Hudson_TrailingDot(), e.getMessage());
        assertThat(j.jenkins.getItems(), hasSize(0));
    }

    @Test
    @Issue("SECURITY-2424")
    public void cannotCreateJobWithTrailingDot_withExistingJob() throws Exception {
        assertThat(j.jenkins.getItems(), hasSize(0));
        j.createFreeStyleProject("jobA");
        assertThat(j.jenkins.getItems(), hasSize(1));
        Failure e = assertThrows(
                "Adding the job should have thrown an exception during checkGoodName",
                Failure.class,
                () -> j.jenkins.createProjectFromXML(
                        "jobA.", new ByteArrayInputStream("<project/>".getBytes(StandardCharsets.UTF_8))));
        assertEquals(Messages.Hudson_TrailingDot(), e.getMessage());
        assertThat(j.jenkins.getItems(), hasSize(1));
    }

    @Issue("SECURITY-2424")
    @Test
    public void cannotCreateJobWithTrailingDot_exceptIfEscapeHatchIsSet() throws Exception {
        String propName = Jenkins.NAME_VALIDATION_REJECTS_TRAILING_DOT_PROP;
        String initialValue = System.getProperty(propName);
        System.setProperty(propName, "false");
        try {
            assertThat(j.jenkins.getItems(), hasSize(0));
            j.jenkins.createProjectFromXML(
                    "jobA.", new ByteArrayInputStream("<project/>".getBytes(StandardCharsets.UTF_8)));
        } finally {
            if (initialValue == null) {
                System.clearProperty(propName);
            } else {
                System.setProperty(propName, initialValue);
            }
        }
        assertThat(j.jenkins.getItems(), hasSize(1));
    }
}
