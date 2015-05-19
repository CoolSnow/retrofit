/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package retrofit;

import com.squareup.okhttp.Response;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import retrofit.http.Body;
import retrofit.http.DELETE;
import retrofit.http.Field;
import retrofit.http.FieldMap;
import retrofit.http.FormUrlEncoded;
import retrofit.http.GET;
import retrofit.http.HEAD;
import retrofit.http.HTTP;
import retrofit.http.Header;
import retrofit.http.Headers;
import retrofit.http.Multipart;
import retrofit.http.PATCH;
import retrofit.http.POST;
import retrofit.http.PUT;
import retrofit.http.Part;
import retrofit.http.PartMap;
import retrofit.http.Path;
import retrofit.http.Query;
import retrofit.http.QueryMap;
import retrofit.http.Streaming;

/** Request metadata about a service interface declaration. */
final class MethodInfo {
  // Upper and lower characters, digits, underscores, and hyphens, starting with a character.
  private static final String PARAM = "[a-zA-Z][a-zA-Z0-9_-]*";
  private static final Pattern PARAM_NAME_REGEX = Pattern.compile(PARAM);
  private static final Pattern PARAM_URL_REGEX = Pattern.compile("\\{(" + PARAM + ")\\}");

  enum RequestBody {
    /** No content-specific logic required. */
    SIMPLE,
    /** Multi-part request body. */
    MULTIPART,
    /** Form URL-encoded request body. */
    FORM_URL_ENCODED
  }

  final Method method;
  final List<CallAdapter> adapters;

  // Method-level details
  CallAdapter adapter;
  CallAdapter.Packaging responsePackaging;
  Type responseType;

  Type requestType;
  RequestBody requestBody = RequestBody.SIMPLE;
  String requestMethod;
  boolean requestHasBody;
  String requestUrl;
  Set<String> requestUrlParamNames;
  String requestQuery;
  com.squareup.okhttp.Headers headers;
  String contentTypeHeader;
  boolean isStreaming;

  // Parameter-level details
  Annotation[] requestParamAnnotations;

  MethodInfo(Method method, List<CallAdapter> adapters) {
    this.method = method;
    this.adapters = adapters;
    parseResponseType();
    parseMethodAnnotations();
    parseParameters();
  }

  private RuntimeException methodError(String message, Object... args) {
    if (args.length > 0) {
      message = String.format(message, args);
    }
    return new IllegalArgumentException(
        method.getDeclaringClass().getSimpleName() + "." + method.getName() + ": " + message);
  }

  private RuntimeException parameterError(int index, String message, Object... args) {
    return methodError(message + " (parameter #" + (index + 1) + ")", args);
  }

  /** Loads {@link #requestMethod} and {@link #requestBody}. */
  private void parseMethodAnnotations() {
    for (Annotation methodAnnotation : method.getAnnotations()) {
      Class<? extends Annotation> annotationType = methodAnnotation.annotationType();
      if (annotationType == DELETE.class) {
        parseHttpMethodAndPath("DELETE", ((DELETE) methodAnnotation).value(), false);
      } else if (annotationType == GET.class) {
        parseHttpMethodAndPath("GET", ((GET) methodAnnotation).value(), false);
      } else if (annotationType == HEAD.class) {
        parseHttpMethodAndPath("HEAD", ((HEAD) methodAnnotation).value(), false);
      } else if (annotationType == PATCH.class) {
        parseHttpMethodAndPath("PATCH", ((PATCH) methodAnnotation).value(), true);
      } else if (annotationType == POST.class) {
        parseHttpMethodAndPath("POST", ((POST) methodAnnotation).value(), true);
      } else if (annotationType == PUT.class) {
        parseHttpMethodAndPath("PUT", ((PUT) methodAnnotation).value(), true);
      } else if (annotationType == HTTP.class) {
        HTTP http = (HTTP) methodAnnotation;
        parseHttpMethodAndPath(http.method(), http.path(), http.hasBody());
      } else if (annotationType == Headers.class) {
        String[] headersToParse = ((Headers) methodAnnotation).value();
        if (headersToParse.length == 0) {
          throw methodError("@Headers annotation is empty.");
        }
        headers = parseHeaders(headersToParse);
      } else if (annotationType == Multipart.class) {
        if (requestBody != RequestBody.SIMPLE) {
          throw methodError("Only one encoding annotation is allowed.");
        }
        requestBody = RequestBody.MULTIPART;
      } else if (annotationType == FormUrlEncoded.class) {
        if (requestBody != RequestBody.SIMPLE) {
          throw methodError("Only one encoding annotation is allowed.");
        }
        requestBody = RequestBody.FORM_URL_ENCODED;
      } else if (annotationType == Streaming.class) {
        if (responseType != Response.class) {
          throw methodError(
              "Only methods having %s as data type are allowed to have @%s annotation.",
              Response.class.getSimpleName(), Streaming.class.getSimpleName());
        }
        isStreaming = true;
      }
    }

    if (requestMethod == null) {
      throw methodError("HTTP method annotation is required (e.g., @GET, @POST, etc.).");
    }
    if (!requestHasBody) {
      if (requestBody == RequestBody.MULTIPART) {
        throw methodError(
            "Multipart can only be specified on HTTP methods with request body (e.g., @POST).");
      }
      if (requestBody == RequestBody.FORM_URL_ENCODED) {
        throw methodError("FormUrlEncoded can only be specified on HTTP methods with request body "
                + "(e.g., @POST).");
      }
    }
  }

  /** Loads {@link #requestUrl}, {@link #requestUrlParamNames}, and {@link #requestQuery}. */
  private void parseHttpMethodAndPath(String method, String path, boolean hasBody) {
    if (requestMethod != null) {
      throw methodError("Only one HTTP method is allowed. Found: %s and %s.", requestMethod,
          method);
    }
    if (path == null || path.length() == 0 || path.charAt(0) != '/') {
      throw methodError("URL path \"%s\" must start with '/'.", path);
    }

    // Get the relative URL path and existing query string, if present.
    String url = path;
    String query = null;
    int question = path.indexOf('?');
    if (question != -1 && question < path.length() - 1) {
      url = path.substring(0, question);
      query = path.substring(question + 1);

      // Ensure the query string does not have any named parameters.
      Matcher queryParamMatcher = PARAM_URL_REGEX.matcher(query);
      if (queryParamMatcher.find()) {
        throw methodError("URL query string \"%s\" must not have replace block. For dynamic query"
            + " parameters use @Query.", query);
      }
    }

    Set<String> urlParams = parsePathParameters(path);

    requestMethod = method;
    requestHasBody = hasBody;
    requestUrl = url;
    requestUrlParamNames = urlParams;
    requestQuery = query;
  }

  com.squareup.okhttp.Headers parseHeaders(String[] headers) {
    com.squareup.okhttp.Headers.Builder builder = new com.squareup.okhttp.Headers.Builder();
    for (String header : headers) {
      int colon = header.indexOf(':');
      if (colon == -1 || colon == 0 || colon == header.length() - 1) {
        throw methodError("@Headers value must be in the form \"Name: Value\". Found: \"%s\"",
            header);
      }
      String headerName = header.substring(0, colon);
      String headerValue = header.substring(colon + 1).trim();
      if ("Content-Type".equalsIgnoreCase(headerName)) {
        contentTypeHeader = headerValue;
      } else {
        builder.add(headerName, headerValue);
      }
    }
    return builder.build();
  }

  /** Loads {@link #responseType} and {@link #adapter}. */
  private void parseResponseType() {
    Type returnType = method.getGenericReturnType();

    // Check for invalid configurations.
    if (returnType == void.class) {
      throw methodError("Service methods cannot return void.");
    }

    for (int i = 0, count = adapters.size(); i < count; i++) {
      CallAdapter adapter = adapters.get(i);
      Type parsedType = adapter.parseType(returnType);
      if (parsedType != null) {
        Class<?> rawType = Types.getRawType(parsedType);
        if (rawType == retrofit.Response.class) {
          if (!(parsedType instanceof ParameterizedType)) {
            throw methodError(""); // TODO
          }
          responseType = Types.getParameterUpperBound((ParameterizedType) parsedType);
          responsePackaging = CallAdapter.Packaging.RESPONSE;
        } else if (rawType == Result.class) {
          if (!(parsedType instanceof ParameterizedType)) {
            throw methodError(""); // TODO
          }
          responseType = Types.getParameterUpperBound((ParameterizedType) parsedType);
          responsePackaging = CallAdapter.Packaging.RESULT;
        } else {
          responseType = parsedType;
          responsePackaging = CallAdapter.Packaging.NONE;
        }

        this.adapter = adapter;

        return;
      }
    }

    StringBuilder builder =
        new StringBuilder("No registered call adapters were able to handle return type ").append(
            returnType).append(". Checked: [ ");
    for (int i = 0, count = adapters.size(); i < count; i++) {
      if (i > 0) builder.append(", ");
      builder.append(adapters.get(i).getClass().getSimpleName());
    }
    builder.append(" ]");
    throw methodError(builder.toString());
  }

  /**
   * Loads {@link #requestParamAnnotations}. Must be called after {@link #parseMethodAnnotations()}.
   */
  private void parseParameters() {
    Type[] methodParameterTypes = method.getGenericParameterTypes();

    Annotation[][] methodParameterAnnotationArrays = method.getParameterAnnotations();
    int count = methodParameterAnnotationArrays.length;
    Annotation[] requestParamAnnotations = new Annotation[count];

    boolean gotField = false;
    boolean gotPart = false;
    boolean gotBody = false;

    for (int i = 0; i < count; i++) {
      Type methodParameterType = methodParameterTypes[i];
      Annotation[] methodParameterAnnotations = methodParameterAnnotationArrays[i];
      if (methodParameterAnnotations != null) {
        for (Annotation methodParameterAnnotation : methodParameterAnnotations) {
          Class<? extends Annotation> methodAnnotationType =
              methodParameterAnnotation.annotationType();

          if (methodAnnotationType == Path.class) {
            String name = ((Path) methodParameterAnnotation).value();
            validatePathName(i, name);
          } else if (methodAnnotationType == Query.class) {
            // Nothing to do.
          } else if (methodAnnotationType == QueryMap.class) {
            if (!Map.class.isAssignableFrom(Types.getRawType(methodParameterType))) {
              throw parameterError(i, "@QueryMap parameter type must be Map.");
            }
          } else if (methodAnnotationType == Header.class) {
            // Nothing to do.
          } else if (methodAnnotationType == Field.class) {
            if (requestBody != RequestBody.FORM_URL_ENCODED) {
              throw parameterError(i, "@Field parameters can only be used with form encoding.");
            }

            gotField = true;
          } else if (methodAnnotationType == FieldMap.class) {
            if (requestBody != RequestBody.FORM_URL_ENCODED) {
              throw parameterError(i, "@FieldMap parameters can only be used with form encoding.");
            }
            if (!Map.class.isAssignableFrom(Types.getRawType(methodParameterType))) {
              throw parameterError(i, "@FieldMap parameter type must be Map.");
            }

            gotField = true;
          } else if (methodAnnotationType == Part.class) {
            if (requestBody != RequestBody.MULTIPART) {
              throw parameterError(i, "@Part parameters can only be used with multipart encoding.");
            }

            gotPart = true;
          } else if (methodAnnotationType == PartMap.class) {
            if (requestBody != RequestBody.MULTIPART) {
              throw parameterError(i,
                  "@PartMap parameters can only be used with multipart encoding.");
            }
            if (!Map.class.isAssignableFrom(Types.getRawType(methodParameterType))) {
              throw parameterError(i, "@PartMap parameter type must be Map.");
            }

            gotPart = true;
          } else if (methodAnnotationType == Body.class) {
            if (requestBody != RequestBody.SIMPLE) {
              throw parameterError(i,
                  "@Body parameters cannot be used with form or multi-part encoding.");
            }
            if (gotBody) {
              throw methodError("Multiple @Body method annotations found.");
            }

            requestType = methodParameterType;
            gotBody = true;
          } else {
            // This is a non-Retrofit annotation. Skip to the next one.
            continue;
          }

          if (requestParamAnnotations[i] != null) {
            throw parameterError(i,
                "Multiple Retrofit annotations found, only one allowed: @%s, @%s.",
                requestParamAnnotations[i].annotationType().getSimpleName(),
                methodAnnotationType.getSimpleName());
          }
          requestParamAnnotations[i] = methodParameterAnnotation;
        }
      }

      if (requestParamAnnotations[i] == null) {
        throw parameterError(i, "No Retrofit annotation found.");
      }
    }

    if (requestBody == RequestBody.SIMPLE && !requestHasBody && gotBody) {
      throw methodError("Non-body HTTP method cannot contain @Body or @TypedOutput.");
    }
    if (requestBody == RequestBody.FORM_URL_ENCODED && !gotField) {
      throw methodError("Form-encoded method must contain at least one @Field.");
    }
    if (requestBody == RequestBody.MULTIPART && !gotPart) {
      throw methodError("Multipart method must contain at least one @Part.");
    }

    this.requestParamAnnotations = requestParamAnnotations;
  }

  private void validatePathName(int index, String name) {
    if (!PARAM_NAME_REGEX.matcher(name).matches()) {
      throw parameterError(index, "@Path parameter name must match %s. Found: %s",
          PARAM_URL_REGEX.pattern(), name);
    }
    // Verify URL replacement name is actually present in the URL path.
    if (!requestUrlParamNames.contains(name)) {
      throw parameterError(index, "URL \"%s\" does not contain \"{%s}\".", requestUrl, name);
    }
  }

  /**
   * Gets the set of unique path parameters used in the given URI. If a parameter is used twice
   * in the URI, it will only show up once in the set.
   */
  static Set<String> parsePathParameters(String path) {
    Matcher m = PARAM_URL_REGEX.matcher(path);
    Set<String> patterns = new LinkedHashSet<String>();
    while (m.find()) {
      patterns.add(m.group(1));
    }
    return patterns;
  }
}
