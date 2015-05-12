package retrofit;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.concurrent.Executor;

final class CallCallAdapter implements CallAdapter {
  private final Executor callbackExecutor;

  CallCallAdapter(Executor callbackExecutor) {
    this.callbackExecutor = callbackExecutor;
  }

  @Override public Type parseType(Type returnType) {
    if (Types.getRawType(returnType) != Call.class) {
      return null;
    }
    if (!(returnType instanceof ParameterizedType)) {
      throw new IllegalStateException(
          "Call return type must be parameterized as Call<Foo> or Call<? extends Foo>");
    }

    Type upperBound = Types.getParameterUpperBound((ParameterizedType) returnType);

    // Ensure the Call type is not a Result or Response.
    Class<?> rawBound = Types.getRawType(upperBound);
    if (rawBound == Result.class || rawBound == Response.class) {
      throw new IllegalStateException(
          "Call<T> cannot use Result or Response as its generic parameter. "
              + "Specify the user type only (e.g., Call<TweetResponse>).");
    }

    return upperBound;
  }

  @Override public <T> Call<T> adapt(Call<T> call, Packaging packaging) {
    return call.copyWithExecutor(callbackExecutor); // TODO just mutate and save an allocation?
  }
}
