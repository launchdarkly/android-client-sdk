package com.launchdarkly.sdk.android.subsystems;

import java.io.Closeable;
import java.util.concurrent.Future;

/**
 * Marker interface for an initializer that is used to load data from the cache and
 * will be run synchronously when the data source is started.
 */
public interface InitializerFromCache {}
