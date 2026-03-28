plugins {
    id("java")
    id("org.springframework.boot") version "3.2.3"
    id("io.spring.dependency-management") version "1.1.4"
}

group = "com.footballbot"
version = "1.0-SNAPSHOT"
java.sourceCompatibility = JavaVersion.VERSION_17
java.targetCompatibility = JavaVersion.VERSION_17

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    // Telegram Bot
    implementation("org.telegram:telegrambots-spring-boot-starter:6.9.7.1")

    // RSS Parser
    implementation("com.rometools:rome:2.1.0")

    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    implementation("org.hibernate.orm:hibernate-community-dialects")

    // JAXB (removed in Java 11+, required by jackson-module-jaxb-annotations)
    implementation("javax.xml.bind:jaxb-api:2.3.1")
    implementation("com.sun.xml.bind:jaxb-impl:2.3.9")

    // Apache Commons Text
    implementation("org.apache.commons:commons-text:1.11.0")

    // Jsoup HTML parser
    implementation("org.jsoup:jsoup:1.17.2")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.test {
    useJUnitPlatform()
}
