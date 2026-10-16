package com.cachecleaner.app;

interface IUserService {
    // Reserved transaction code Shizuku uses to tear the service down
    void destroy() = 16777114;

    // Returns "<exit code>\n<output>"
    String trimCaches() = 1;
}
