@ApplicationModule(
        allowedDependencies = {"github::github-events", "github", "shared :: shared", "user :: api", "github :: api"}
)
package com.sprintstart.sprintstartbackend.ingestion;

import org.springframework.modulith.ApplicationModule;
