package versioned.host.exp.exponent.modules.universal.av;

import com.facebook.react.bridge.ReactContext;
import com.facebook.react.modules.network.NetworkingModule;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;

import java.util.Map;

import expolib_v1.okhttp3.OkHttpClient;

public class SharedCookiesDataSourceFactory implements DataSource.Factory {
  private final DataSource.Factory mDataSourceFactory;

  public SharedCookiesDataSourceFactory(ReactContext reactApplicationContext, String userAgent, Map<String, Object> requestHeaders) {
    OkHttpClient reactNativeOkHttpClient = reactApplicationContext.getNativeModule(NetworkingModule.class).mClient;
    mDataSourceFactory = new DefaultDataSourceFactory(reactApplicationContext, null, new CustomHeadersOkHttpDataSourceFactory(reactNativeOkHttpClient, userAgent, requestHeaders));
  }

  @Override
  public DataSource createDataSource() {
    return mDataSourceFactory.createDataSource();
  }
}