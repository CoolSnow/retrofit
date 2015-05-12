// Copyright 2013 Square, Inc.
package retrofit;

import com.google.gson.JsonParseException;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import retrofit.converter.Converter;
import retrofit.converter.GsonConverter;
import retrofit.http.GET;
import retrofit.http.Headers;
import retrofit.http.Streaming;

import static com.squareup.okhttp.mockwebserver.SocketPolicy.DISCONNECT_AT_START;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class RestAdapterTest {
  private interface Example {
    @Headers("Foo: Bar")
    @GET("/") String something();
    @Headers("Foo: Bar")
    @GET("/") void something(Callback<String> callback);
    @GET("/") Response direct();
    @GET("/") void direct(Callback<Response> callback);
    @GET("/") @Streaming Response streaming();
  }
  private interface InvalidExample extends Example {
  }

  @Rule public final MockWebServerRule server = new MockWebServerRule();

  private Example example;
  private Converter converter;

  @Before public void setUp() {
    OkHttpClient client = new OkHttpClient();

    converter = spy(new GsonConverter());

    example = new RestAdapter.Builder() //
        .client(client)
        .endpoint(server.getUrl("/").toString())
        .converter(converter)
        .build()
        .create(Example.class);
  }

  @Test public void objectMethodsStillWork() {
    assertThat(example.hashCode()).isNotZero();
    assertThat(example.equals(this)).isFalse();
    assertThat(example.toString()).isNotEmpty();
  }

  @Test public void interfaceWithExtendIsNotSupported() {
    try {
      new RestAdapter.Builder().endpoint("http://foo/").build().create(InvalidExample.class);
      fail();
    } catch (IllegalArgumentException e) {
      assertThat(e).hasMessage("Interface definitions must not extend other interfaces.");
    }
  }

  @Test public void http204SkipsConverter() {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 204 Nothin"));
    assertThat(example.something()).isNull();
    verifyNoMoreInteractions(converter);
  }

  @Test public void http204Response() {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 204 Nothin"));
    Response response = example.direct();
    assertThat(response.code()).isEqualTo(204);
  }

  @Test public void http204WithBodyThrows() {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 204 Nothin").setBody("Hey"));
    try {
      example.something();
      fail();
    } catch (RetrofitError e) {
      assertThat(e).hasMessage("204 response must not include body.");
      Throwable cause = e.getCause();
      assertThat(cause).isInstanceOf(IllegalStateException.class);
      assertThat(cause).hasMessage("204 response must not include body.");
    }
  }

  @Test public void http205SkipsConverter() {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 205 Nothin"));
    assertThat(example.something()).isNull();
    verifyNoMoreInteractions(converter);
  }

  @Test public void http205Response() {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 205 Nothin"));
    Response response = example.direct();
    assertThat(response.code()).isEqualTo(205);
  }

  @Test public void http205WithBodyThrows() {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 205 Nothin").setBody("Hey"));
    try {
      example.something();
      fail();
    } catch (RetrofitError e) {
      assertThat(e).hasMessage("205 response must not include body.");
      Throwable cause = e.getCause();
      assertThat(cause).isInstanceOf(IllegalStateException.class);
      assertThat(cause).hasMessage("205 response must not include body.");
    }
  }

  @Test public void successfulRequestResponseWhenMimeTypeMissing() throws Exception {
    server.enqueue(new MockResponse().setBody("Hi").removeHeader("Content-Type"));
    String string = example.something();
    assertThat(string).isEqualTo("Hi");
  }

  @Test public void malformedResponseThrowsConversionException() throws Exception {
    server.enqueue(new MockResponse().setBody("{"));
    try {
      example.something();
      fail();
    } catch (RetrofitError e) {
      assertThat(e.getKind()).isEqualTo(RetrofitError.Kind.UNEXPECTED);
      assertThat(e.getResponse().code()).isEqualTo(200);
      assertThat(e.getCause()).isInstanceOf(JsonParseException.class);
      assertThat(e.getResponse().body()).isNull();
    }
  }

  @Test public void errorResponseThrowsHttpError() throws Exception {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 500 Broken"));

    try {
      example.something();
      fail();
    } catch (RetrofitError e) {
      assertThat(e.getKind()).isEqualTo(RetrofitError.Kind.HTTP);
      assertThat(e.getResponse().code()).isEqualTo(500);
      assertThat(e.getSuccessType()).isEqualTo(String.class);
    }
  }

  @Test public void clientExceptionThrowsNetworkError() throws Exception {
    server.enqueue(new MockResponse().setBody("Hi").setSocketPolicy(DISCONNECT_AT_START));

    try {
      example.something();
      fail();
    } catch (RetrofitError e) {
      assertThat(e.getKind()).isEqualTo(RetrofitError.Kind.NETWORK);
    }
  }

  @Test public void getResponseDirectly() throws Exception {
    server.enqueue(new MockResponse().setBody("Hey"));
    Response response = example.direct();
    assertThat(response.body().string()).isEqualTo("Hey");
  }

  @Test public void streamingResponse() throws Exception {
    server.enqueue(new MockResponse().setBody("Hey").setBodyDelay(500, MILLISECONDS));
    Response response = example.streaming();
    long startNs = System.nanoTime();
    response.body().string();
    long tookNs = System.nanoTime() - startNs;
    assertThat(tookNs).isGreaterThanOrEqualTo(500);
  }

  @Test public void getResponseDirectlyAsync() throws Exception {
    server.enqueue(new MockResponse().setBody("Hey"));

    final AtomicReference<Response> responseRef = new AtomicReference<Response>();
    final CountDownLatch latch = new CountDownLatch(1);
    example.direct(new Callback<Response>() {
      @Override public void success(Response response, Response response2) {
        responseRef.set(response);
        latch.countDown();
      }

      @Override public void failure(RetrofitError error) {
        throw new AssertionError();
      }
    });
    assertTrue(latch.await(1, TimeUnit.SECONDS));

    assertThat(responseRef.get().body().string()).isEqualTo("Hey");
  }

  @Test public void getAsync() throws Exception {
    server.enqueue(new MockResponse().setBody("Hey"));

    final AtomicReference<String> bodyRef = new AtomicReference<String>();
    final CountDownLatch latch = new CountDownLatch(1);
    example.something(new Callback<String>() {
      @Override public void success(String body, Response response) {
        bodyRef.set(body);
        latch.countDown();
      }

      @Override public void failure(RetrofitError error) {
        throw new AssertionError();
      }
    });
    assertTrue(latch.await(1, TimeUnit.SECONDS));

    assertThat(bodyRef.get()).isEqualTo("Hey");
  }

  @Test public void errorAsync() throws Exception {
    server.enqueue(new MockResponse().setStatus("HTTP/1.1 500 Broken!").setBody("Hey"));

    final AtomicReference<RetrofitError> errorRef = new AtomicReference<RetrofitError>();
    final CountDownLatch latch = new CountDownLatch(1);
    example.something(new Callback<String>() {
      @Override public void success(String s, Response response) {
        throw new AssertionError();
      }

      @Override public void failure(RetrofitError error) {
        errorRef.set(error);
        latch.countDown();
      }
    });
    assertTrue(latch.await(1, TimeUnit.SECONDS));

    RetrofitError error = errorRef.get();
    assertThat(error.getResponse().code()).isEqualTo(500);
    assertThat(error.getResponse().message()).isEqualTo("Broken!");
    assertThat(error.getSuccessType()).isEqualTo(String.class);
    assertThat(error.getBody()).isEqualTo("Hey");
  }
}
