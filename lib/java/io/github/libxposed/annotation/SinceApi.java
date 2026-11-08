package io.github.libxposed.annotation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.CLASS)
public @interface SinceApi {
    int value();
}
