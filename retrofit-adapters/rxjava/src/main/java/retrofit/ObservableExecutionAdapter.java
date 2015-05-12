package retrofit;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.subscriptions.Subscriptions;

/**
 * TODO docs
 */
public final class ObservableExecutionAdapter implements CallAdapter {
  /**
   * TODO
   */
  private static ObservableExecutionAdapter create() {
    return new ObservableExecutionAdapter();
  }

  private ObservableExecutionAdapter() {
  }

  @Override public Type parseType(Type returnType) {
    if (Types.getRawType(returnType) != Observable.class) {
      return null;
    }
    if (returnType instanceof ParameterizedType) {
      return Types.getParameterUpperBound((ParameterizedType) returnType);
    }
    throw new IllegalStateException("Observable return type must be parameterized"
        + " as Observable<Foo> or Observable<? extends Foo>");
  }

  @Override public <T> Observable<?> adapt(final Call<T> call, final Packaging packaging) {
    // TODO switch on packaging here for creation of different OnSubscribe classes?
    return Observable.create(new Observable.OnSubscribe<Object>() {
      @Override public void call(final Subscriber<? super Object> subscriber) {
        call.enqueue(new Callback<T>() {
          @Override public void success(Response<T> response) {
            if (subscriber.isUnsubscribed()) {
              return;
            }

            switch (packaging) {
              case RESULT:
                subscriber.onNext(Result.fromResponse(response));
                subscriber.onCompleted();
                break;
              case RESPONSE:
                subscriber.onNext(response);
                subscriber.onCompleted();
                break;
              case NONE:
                if (response.isSuccess()) {
                  subscriber.onNext(response.body());
                  subscriber.onCompleted();
                } else {
                  subscriber.onError(new IOException()); // TODO non-suck message
                }
                break;
              default:
                throw new AssertionError();
            }
          }

          @Override public void failure(Throwable t) {
            if (subscriber.isUnsubscribed()) {
              return;
            }

            switch (packaging) {
              case RESULT:
                subscriber.onNext(Result.fromError(t));
                subscriber.onCompleted();
                break;
              case RESPONSE:
              case NONE:
                subscriber.onError(new IOException()); // TODO non-suck message
                break;
              default:
                throw new AssertionError();
            }
          }
        });

        // Attempt to cancel the call if it is still in-flight on unsubscription.
        subscriber.add(Subscriptions.create(new Action0() {
          @Override public void call() {
            call.cancel();
          }
        }));
      }
    });
  }
}
