package retrofit;

import java.lang.reflect.Type;

/**
 * TODO
 */
public interface CallAdapter {
  /**
   * TODO kill this and make a Gson-esque TypeAdapterFactory setup for arbitrary packaging?
   */
  enum Packaging {
    RESULT,
    RESPONSE,
    NONE
  }

  /**
   * TODO
   */
  Type parseType(Type returnType);

  /**
   * TODO
   */
  <T> Object adapt(Call<T> call, Packaging packaging);
}
