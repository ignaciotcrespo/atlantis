package com.echsylon.atlantis.template;

import com.echsylon.atlantis.internal.json.JsonParser;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Verifies expected behavior on the {@link Template} class.
 */
public class TemplateTest {
    private static final Template ROOT;

    static {
        ROOT = new JsonParser().fromJson("{\"requests\": [{" +
                "  \"headers\": \"Content-Type: text/plain\n\", " +
                "  \"method\": \"POST\", " +
                "  \"url\": \"scheme://host/path?q1=v1&q2=v2\", " +
                "  \"responses\": [{" +
                "    \"responseCode\": {" +
                "      \"code\": 200, " +
                "      \"name\": \"OK\"" +
                "    }, " +
                "    \"mime\": \"application/json\", " +
                "    \"text\": \"\\\"{}\\\"\"" +
                "  }]" +
                "}]}", Template.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "ArraysAsListWithZeroOrOneArgument"})
    public void testFindResponse() throws Exception {
        // Verify empty url doesn't return result when there is no matching result.
        assertThat(ROOT.findRequest("", "POST", null), is(nullValue()));

        // Verify query string is matched exactly
        assertThat(ROOT.findRequest("scheme://host/path", "POST", null), is(nullValue()));                    // no query
        assertThat(ROOT.findRequest("scheme://host/path?q1=v1", "POST", null), is(nullValue()));              // too short query
        assertThat(ROOT.findRequest("scheme://host/path?q1=v1&q3=v3", "POST", null), is(nullValue()));        // no matching query
        assertThat(ROOT.findRequest("scheme://host/path?q1=v1&q2=v2&q3=v3", "POST", null), is(nullValue()));  // too long query

        // Verify method is matched
        assertThat(ROOT.findRequest("scheme://host/path?q1=v1&q2=v2", "PUT", null), is(nullValue()));

        // Verify headers are matched if given (even if empty)
        assertThat(ROOT.findRequest("scheme://host/path?q1=v1&q2=v2", "POST", Collections.EMPTY_MAP), is(nullValue()));

        // Verify success
        HashMap<String, String> exactHeaders = new HashMap<>();
        exactHeaders.put("Content-Type", "text/plain");
        assertThat(ROOT.findRequest("scheme://host/path?q1=v1&q2=v2", "POST", null), is(notNullValue()));         // ignore headers
        assertThat(ROOT.findRequest("scheme://host/path?q1=v1&q2=v2", "POST", exactHeaders), is(notNullValue())); // exact headers
    }

}
