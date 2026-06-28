# Gradle Wrapper

The `gradle-wrapper.jar` file is required but too large to upload via API.

## To get the wrapper:

### Option 1: Download manually
Download from: https://raw.githubusercontent.com/gradle/gradle/v8.2.0/gradle/wrapper/gradle-wrapper.jar
Place in: `gradle/wrapper/gradle-wrapper.jar`

### Option 2: Use local Gradle
If you have Gradle installed locally, run:
```bash
gradle wrapper --gradle-version 8.2
```

### Option 3: GitHub Actions will handle it
The GitHub Actions workflow will automatically download the correct wrapper.
