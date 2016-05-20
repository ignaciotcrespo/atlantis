package com.echsylon.sample;

import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

public class MainActivity extends AppCompatActivity {
    private static final int MENU_GET = Menu.FIRST;

    private ProgressDialog progress;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        output = (TextView) findViewById(R.id.output);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_GET, Menu.NONE, "Get")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_GET:
                performNetworkRequest(BuildConfig.BASE_URL, output);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Tries to send a request to the given url and read the response from it. The work is done in a
     * spawned worker thread and upon finish the result is written to the given output text view. As
     * a courtesy this method will show a progress dialog while working (and dismiss it when done).
     *
     * @param urlString       The target url.
     * @param resultContainer The text view to output the result to. May be null.
     */
    private void performNetworkRequest(final String urlString, final TextView resultContainer) {
        new AsyncTask<Void, Void, String>() {
            @Override
            protected void onPreExecute() {
                if (progress != null && progress.isShowing())
                    progress.dismiss();

                progress = ProgressDialog.show(MainActivity.this, null, "fetching content...", true);
            }

            @Override
            protected String doInBackground(Void... params) {
                try {
                    URL url = new URL(urlString);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");

                    return readNetworkResponse(connection.getInputStream());
                } catch (MalformedURLException e) {
                    return printThrowableToString(e);
                } catch (IOException e) {
                    return printThrowableToString(e);
                }
            }

            @Override
            protected void onPostExecute(String result) {
                progress.dismiss();
                if (resultContainer != null)
                    resultContainer.setText(result);
            }
        }.execute();
    }

    /**
     * Manually tries to read all content from the given input stream and return it as a string.
     * Would anything go wrong, then the exception stack trace is returned as a string. Any internal
     * stream wrappers are closed, but the given input stream is left to the caller to deal with.
     *
     * @param stream The input stream to read content content from.
     * @return A string containing either the read content or an exception stack trace. Never null.
     */
    private String readNetworkResponse(InputStream stream) {
        InputStream inputStream = new BufferedInputStream(stream);
        InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

        StringBuilder stringBuilder = new StringBuilder();
        String line;

        try {
            while ((line = bufferedReader.readLine()) != null)
                stringBuilder.append(line);
            return stringBuilder.toString();
        } catch (IOException e) {
            return printThrowableToString(e);
        } finally {
            closeSilently(bufferedReader);
            closeSilently(inputStreamReader);
            closeSilently(inputStream);
        }
    }

    /**
     * Tries to silently close something that claims to be closeable. Any exceptions thrown are
     * silently consumed.
     *
     * @param closeable The thing to close.
     */
    private void closeSilently(Closeable closeable) {
        if (closeable == null)
            return;

        try {
            closeable.close();
        } catch (IOException e) {
            // Respectfully ignore any exceptions
        }
    }

    /**
     * Writes the stack trace of the given throwable to a string variable and returns it.
     *
     * @param throwable The stack trace container.
     * @return A string containing the stack trace. Never null.
     */
    private String printThrowableToString(Throwable throwable) {
        if (throwable == null)
            return "";

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        throwable.printStackTrace(printWriter);
        String result = stringWriter.toString();

        closeSilently(stringWriter);
        closeSilently(printWriter);

        return result;
    }
}