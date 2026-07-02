package com.blocklogs;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Loads runtime-only libraries (the SQLite JDBC driver) via Paper's Maven library resolver, so we do
 * not have to shade them into the plugin jar. Referenced from {@code paper-plugin.yml} as {@code loader}.
 */
@SuppressWarnings("UnstableApiUsage")
public class BlockLogsLoader implements PluginLoader {

    /** Keep in sync with the {@code compileOnly} coordinate in build.gradle.kts. */
    private static final String SQLITE_JDBC = "org.xerial:sqlite-jdbc:3.50.1.0";

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        // Paper 26.2 rejects using repo1.maven.org directly (Maven Central ToS); use Paper's mirror.
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());
        resolver.addDependency(new Dependency(new DefaultArtifact(SQLITE_JDBC), null));
        classpathBuilder.addLibrary(resolver);
    }
}
