package retrofit;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.ResponseBody;
import java.io.IOException;
import retrofit.converter.Converter;

public final class Response<T> {
  private final com.squareup.okhttp.Response rawResponse;
  private final T body;
  private final ResponseBody errorBody;
  private final Converter converter;

  Response(com.squareup.okhttp.Response rawResponse, T body, ResponseBody errorBody,
      Converter converter) {
    this.rawResponse = rawResponse;
    this.body = body;
    this.errorBody = errorBody;
    this.converter = converter;
  }

  /** The raw response from the HTTP client. */
  public com.squareup.okhttp.Response raw() {
    return rawResponse;
  }

  /** HTTP status code. */
  public int code() {
    return rawResponse.code();
  }

  public Headers headers() {
    return rawResponse.headers();
  }

  /** {@code true} if {@link #code()} is in the range [200..300). */
  public boolean isSuccess() {
    return rawResponse.isSuccessful();
  }

  /** The deserialized response body of a {@linkplain #isSuccess() successful} response. */
  public T body() {
    return body;
  }

  /** The raw response body of an {@linkplain #isSuccess() unsuccessful} response. */
  public ResponseBody errorBody() {
    return errorBody;
  }

  /**
   * The deserialize the response body of an {@linkplain #isSuccess() unsuccessful} response to
   * {@code E}.
   */
  @SuppressWarnings("unchecked")
  public <E> E errorBodyAs(Class<E> errorClass) {
    try {
      return (E) converter.fromBody(errorBody, errorClass);
    } catch (IOException e) {
      throw new AssertionError(e); // Body is buffered.
    }
  }
}
