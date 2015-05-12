package retrofit;

import java.io.IOException;

import static retrofit.Utils.checkNotNull;

/** The result of executing an HTTP request. */
public final class Result<T> {
  static <T> Result<T> fromError(Throwable error) {
    return new Result<T>(null, checkNotNull(error, "error == null"));
  }

  static <T> Result<T> fromResponse(Response<T> response) {
    return new Result<T>(checkNotNull(response, "response == null"), null);
  }

  private final Response<T> response;
  private final Throwable error;

  Result(Response<T> response, Throwable error) {
    this.response = response;
    this.error = error;
  }

  /**
   * The response received from executing an HTTP request. Only present when {@link #isError()} is
   * {@code false}, {@code null} otherwise.
   */
  public Response<T> response() {
    return response;
  }

  /**
   * The error experienced while attempting to execute an HTTP request. Only present when {@link
   * #isError()} is {@code true}, {@code null} otherwise.
   * <p>
   * If the error is an {@link IOException} then there was a problem with the transport to the
   * remote server. Any other exception type indicates an unexpected failure and should be
   * considered fatal (configuration error, programming error, etc.).
   */
  public Throwable error() {
    return error;
  }

  /** {@code true} if the request resulted in an error. See {@link #error()} for the cause. */
  public boolean isError() {
    return error != null;
  }
}
