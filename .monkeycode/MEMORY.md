# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[Project Knowledge Summary]
- Date: 2026-08-08
- Context: Discovered by Agent while performing build verification for utils-support-enhance-starter
- Category: Build Methods
- Instructions:
  - The devbox has no preinstalled JDK or Maven. JDK 25 was installed to /opt/jdk25, Maven 3.9.9 to /opt/maven. Use `export JAVA_HOME=/opt/jdk25` and prepend `/opt/jdk25/bin:/opt/maven/bin` to PATH before any mvn command.
  - The project targets Java 25 (see root pom `java.version`). The root reactor cannot be built as-is: some modules referenced by parent POMs do not exist in the repo (e.g. utils-support-filesystem-parent/utils-support-ffmpeg-javacv-starter, utils-support-gateway-parent/utils-support-gateway-agent-starter). Always build a single module from its own directory instead of from the root.
  - Maven remote repo `github-resource` (https://maven.pkg.github.com/CHTK001/utils-support-resource-parent) requires auth (401 Unauthorized). Dependencies between local modules must be installed into the local repository first.
  - To build a starter module: (1) `mvn install -N -DskipTests` on root pom and each parent POM in the chain, (2) `mvn install -DskipTests` on utils-support-common-starter, (3) `mvn install -DskipTests` in the target module directory.
