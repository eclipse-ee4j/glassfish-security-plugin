# Eclipse GlassFish Command Security Maven Plugin

[Eclipse GlassFish Command Security Maven Plugin](https://github.com/eclipse-ee4j/glassfish-security-plugin) is a Maven plugin for checking that
AdminCommand implementers address command authorization.

## Example

```
<plugin>
    <groupId>org.glassfish.build</groupId>
    <artifactId>command-security-maven-plugin</artifactId>
    <version>${glassfish.command.security.version}</version>
    <configuration>
        <isFailureFatal>${command.security.maven.plugin.isFailureFatal}</isFailureFatal>
    </configuration>
</plugin>
```
