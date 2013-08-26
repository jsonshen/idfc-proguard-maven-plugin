package com.idfconnect.devtools.maven.proguard;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FilenameUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.ProGuard;

/**
 * A Maven 3.1 plug-in for using ProGuard to obfuscate project artifacts
 * 
 * @author Richard Sand
 */
@Mojo(name = "obfuscate", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public final class ProguardMojo extends AbstractMojo {
    /**
     * Internal class for holding a single ProGuard command-line option
     */
    class Option {
        private String name  = null;
        private String value = null;

        public Option() {
        }

        public Option(String name) {
            this.name = name;
            if (getLog().isDebugEnabled())
                getLog().debug("Adding option: " + toString());
        }

        public Option(String name, String value) {
            this.name = name;
            this.value = value;
            if (getLog().isDebugEnabled())
                getLog().debug("Adding option: " + toString());
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            if (name != null) {
                builder.append('-');
                builder.append(name);
                if (value != null) {
                    builder.append(' ');
                    builder.append(value);
                }
            }
            return builder.toString();
        }
    }

    /**
     * Internal class for holding a single ProGuard output file as an artifact
     */
    class InternalOutputArtifact {
        private OutputArtifact data = null;

        InternalOutputArtifact() {
            this.data = new OutputArtifact();
        }

        InternalOutputArtifact(OutputArtifact data) {
            this.data = data;
        }

        String getGroupId() {
            return (data.getGroupId() != null) ? data.getGroupId() : mavenProject.getGroupId();
        }

        String getArtifactId() {
            return (data.getArtifactId() != null) ? data.getArtifactId() : mavenProject.getArtifactId();
        }

        String getVersion() {
            return (data.getVersion() != null) ? data.getVersion() : mavenProject.getVersion();
        }

        String getType() {
            return (data.getType() != null) ? data.getType() : "jar";
        }

        String getClassifier() {
            if (data.getClassifier() != null)
                return data.getClassifier();
            return (useDefaultOutputArtifactClassifiers) ? defaultOutputArtifactClassifier : null;
        }

        File getFile() {
            return resolveAbsoluteFile((data.getFile() != null) ? data.getFile() : getFileName(), buildDirectory);
        }

        boolean isAttach() {
            return data.isAttach();
        }

        String getFileName() {
            if (data.getFile() != null)
                return getFile().getName();

            StringBuffer sb = new StringBuffer();
            sb.append(getArtifactId()).append('-').append(getVersion());
            if (getClassifier() != null)
                sb.append('-').append(getClassifier());
            sb.append('.').append(getType());
            return sb.toString();
        }
    }
    
    // ////////////////////////////////////////////////////
    // CONFIG AND CONTROL PARAMETERS
    // ////////////////////////////////////////////////////
    

    /**
     * Set this to 'true' to bypass ProGuard processing entirely.
     * 
     */
    @Parameter(defaultValue = "false", property = "proguard.skip")
    private boolean                 skip                         = false;

    /**
     * Set this to 'true' to test the plug-in without launching ProGuard. This will simply show you how the plug-in builds the ProGuard invocation arguments
     * 
     */
    @Parameter(defaultValue = "false", property = "proguard.test")
    private boolean                 test                         = false;

    /**
     * Set this to 'true' to bypass attaching any resulting artifacts
     * 
     */
    @Parameter(defaultValue = "false", property = "proguard.dontattach")
    private boolean                 dontattach                   = false;

    /**
     * Base directory for all operations. Defaults to <code>${project.build.directory}</code>.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "proguard.builddir", required = true)
    private File                    buildDirectory;

    /**
     * Output directory for ProGuard files, such as the mapping file. Defaults to <code>${project.build.directory}/proguard</code>.
     */
    @Parameter(defaultValue = "${project.build.directory}/proguard", property = "proguard.output", required = true)
    private File                    proguardOutputDirectory;

    /**
     * Includes additional ProGuard configuration options from the provided file. This defaults to
     * <code>${basedir}/src/main/config/${project.artifactId}-maven.pro</code>. If no such file exists, the parameter is ignored. This behavior can be disabled
     * by the parameter <em>ignoreIncludeFile</em>
     */
    @Parameter(defaultValue = "${basedir}/src/main/config/${project.artifactId}-maven.pro")
    private String                  proguardIncludeFile;

    /**
     * Set this to 'true' to disable the parameter <em>proguardInclude</em>
     */
    @Parameter(defaultValue = "false", property = "proguard.ignoreincludefile")
    private boolean                 ignoreIncludeFile            = false;

    /**
     * Other arbitrary ProGuard configuration options
     */
    @Parameter
    private Map<String, String>     options;

    /**
     * Specifies to obfuscate the input class files. Setting this to <em>false</em> sets the ProGuard option <code>-dontobfuscate</em>
     */
    @Parameter(defaultValue = "true")
    private boolean                 obfuscate                    = true;

    /**
     * Specifies not to shrink the input class files. Setting this to <em>false</em> sets the ProGuard option <code>-dontshrink</em>
     */
    @Parameter(defaultValue = "true")
    private boolean                 shrink                       = true;

    /**
     * Tells ProGuard not to error out if there are unresolved references to classes or interfaces. Defaults to false
     */
    @Parameter(defaultValue = "false")
    private boolean                 dontwarn                     = false;

    // ////////////////////////////////////////////////////
    // LIBRARY PARAMETERS
    // ////////////////////////////////////////////////////

    /**
     * Additional external (e.g. non-artifact) libraries to include to Proguard as <em>libraryjars</em> parameters, e.g. <code>${java.home}/lib/jsse.jar</code>.
     * Note that the preferred way to specify jars is referencing dependent artifacts with the <em>libraryArtifacts</em> element.
     */
    @Parameter
    private List<String>            libraryJarPaths;

    /**
     * A list of additional project artifacts, specified by coordinate Strings, e.g. <code>javax.servlet:javax.servlet-api:3.0.1</code>, to be included as
     * <em>libraryjars</em> parameters to ProGuard. The version must be included in the coordinates. The plugin will attempt to resolve the artifact, so the artifact need not be a dependency in the project.
     * Also note that by default all project resolved dependencies are already automatically added by the plugin and do not need to be explicitly configured with this parameter.
     */
    @Parameter
    private List<String>            libraryArtifacts;

    /**
     * Coordinates of project dependencies which should be explicitly excluded from <em>libraryjars</em>. Note that this parameter has no effect if the
     * parameter <em>includeDependencies</em> is set to <em>false</em>
     */
    @Parameter
    private List<String>            excludeLibraryArtifacts;

    /**
     * Specifies that project compile dependencies should be automatically added as <em>libraryjars</em>
     */
    @Parameter(defaultValue = "true")
    private boolean                 includeDependencies          = true;

    /**
     * Automatically adds the java runtime jar <code>${java.home}/lib/rt.jar</code> to the ProGuard <em>libraryjars</em>. Defaults to true.
     */
    @Parameter(defaultValue = "true")
    private boolean                 includeJreRuntimeJar         = true;
    @Parameter(defaultValue = "${java.home}/lib/rt.jar", readonly = true)
    private String                  includedJreRuntimeJar;

    // ////////////////////////////////////////////////////
    // INPUT PARAMETERS
    // ////////////////////////////////////////////////////

    /**
     * Specifies the <em>primary</em> input file name (e.g. classes folder, jar, war, ear, zip, etc.) to be processed. This defaults to the typical output of
     * the packaging phase, which is <code>${project.build.finalName}.${project.packaging}</code>. However, if you are obfuscating before the packaging phase,
     * you would typically want to set this to <code>${project.build.outputDirectory}</code> instead to indicate the classes directory. If a relative path is
     * specified, it will be relative to the base directory.
     */
    @Parameter(defaultValue = "${project.build.finalName}.${project.packaging}")
    private String                  inputFile;

    /**
     * Specifies the ProGuard-syntax input filter to apply to the input file. Note that this only applies to the input file. It does not apply to other
     * jars specified via <em>inputArtifacts</em> or <em>inputJarPaths</em>. 
     */
    @Parameter
    private String                  inputFileFilter;

    /**
     * This parameter will generate additional <em>injars</em> input entries to ProGuard from the project artifacts. Set the artifact names in coordinate String
     * form, e.g. <code>com.idfconnect.someproject:SomeLibrary</code> to pull it from the project dependencies. The version does <em>not</em> need to be included in the String.
     * The artifact <em>must</em> already be a resolved dependency in the project - the plugin will <em>not</em> attempt to resolve the artifact.
     */
    @Parameter
    private List<String>            inputArtifacts;

    /**
     * Additional external (e.g. non-artifact) input to include to Proguard as <em>injars</em> parameters. 
     * Note that the preferred way to include input is referencing dependent artifacts with the <em>inputArtifacts</em> element.
     * You may optionally specify a ProGuard input filter at the end of each path
     */
    @Parameter
    private List<String>            inputJarPaths;

    /**
     * Set this to 'true' to bypass ProGuard processing if any <em>injars</em> entries do not exists
     */
    @Parameter(defaultValue = "false")
    private boolean                 injarNotExistsSkip           = false;

    /**
     * Automatically exclude via ProGuard filter the manifests from any <em>injars</em>. Note that if this is set to false, such a filter may still be included
     * explicitly on any <em>injar</em> entry
     */
    @Parameter(defaultValue = "true")
    private boolean                 excludeManifests             = true;

    // ////////////////////////////////////////////////////
    // OUTPUT PARAMETERS
    // ////////////////////////////////////////////////////

    /**
     * Specifies the output artifacts that ProGuard will produce. These are specified in the form of artifacts. If no output artifacts are specified, then the
     * plugin will create a single default output artifact of the form <code>${project.build.finalName}-${project.version}-small.${project.packaging}</code>.
     * Note the default classifier: "small"
     */
    @Parameter
    private List<OutputArtifact>    outputArtifacts;

    /**
     * The default output artifact type, which is <code>${project.packaging}</code>
     * 
     */
    @Parameter(defaultValue = "${project.packaging}", readonly = true, required = true)
    private String                  outputArtifactType;

    /**
     * Specifies the default output artifact Classifier. The default value is "small".
     */
    @Parameter(defaultValue = "small")
    private String                  defaultOutputArtifactClassifier;

    /**
     * Indicates whether the output artifacts should have the default classifier appended to their filenames if they don't explicity set a classifier value.
     * Default value is true.
     */
    @Parameter(defaultValue = "true")
    private boolean                 useDefaultOutputArtifactClassifiers;

    /**
     * Explicitly sets the ProGuard <em>outjars</em> parameter, <u>in addition to</u> those values generated by the <em>outputArtifacts</em> element. If not
     * specified, the default for <em>outputArtifacts</em> will be used
     */
    @Parameter
    private List<String>            outJars;
    
    // ////////////////////////////////////////////////////
    // PARAMETERS FOR PROGUARD ARTIFACTS
    // ////////////////////////////////////////////////////

    /**
     * Indicates whether <em>printmapping</em> should be specified. Defaults to true.
     */
    @Parameter(defaultValue = "true")
    private boolean                 printMapping                 = true;

    /**
     * Filename to use with <em>printmapping</em>. Defaults to <em>proguard.map</em>
     */
    @Parameter(defaultValue = "proguard.map")
    private String                  printMappingFile             = "proguard.map";

    /**
     * Indicates whether the ProGuard <em>printmapping</em> file should be attached to the project as an artifact. Defaults to true.
     */
    @Parameter(defaultValue = "true")
    private boolean                 printMappingAttachAsArtifact = true;

    /**
     * Indicates whether <em>printseeds</em> should be specified. Defaults to false.
     */
    @Parameter(defaultValue = "false")
    private boolean                 printSeeds                   = false;

    /**
     * Filename to use with <em>printseeds</em>. Defaults to <em>proguard.seeds</em>
     */
    @Parameter(defaultValue = "proguard.seeds")
    private String                  printSeedsFile               = "proguard.seeds";

    /**
     * Indicates whether the ProGuard <em>printseeds</em> file should be attached to the project as an artifact. Defaults to false.
     */
    @Parameter(defaultValue = "false")
    private boolean                 printSeedsAttachAsArtifact   = false;

    // ////////////////////////////////////////////////////
    // MAVEN PARAMETERS
    // ////////////////////////////////////////////////////

    /**
     * Set to false to include META-INF/maven/**
     */
    @Parameter(defaultValue = "true")
    private boolean                 excludeMavenDescriptor;

    /**
     * The Maven project reference where the plug-in is being executed. This value is read-only and is populated by Maven
     * 
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject            mavenProject;

    /**
     * The Maven project helper component
     * 
     */
    @Component
    private MavenProjectHelper      mavenProjectHelper;

    /**
     * RepositorySystemSession
     */
    @Parameter(defaultValue = "${repositorySystemSession}", required = true, readonly = true)
    private RepositorySystemSession repoSession;

    /**
     * RepositorySystem
     */
    @Component
    private RepositorySystem        repoSystem;

    /**
     * The project's remote repositories to use for the resolution.
     */
    @Parameter(defaultValue = "${project.remoteProjectRepositories}", required = true, readonly = true)
    private List<RemoteRepository>  remoteRepositories;

    // //
    // Other instance variables
    // //

    // The ProGuard arguments list
    private List<Option>            args                         = null;

    // The project's artifact map
    private Map<String, Artifact>   projectArtifactMap           = null;

    // Plugin logger
    Log                             log                          = getLog();

    // The aggregate list of all input files
    List<File>                      inputFileList                = null;

    // The aggregate list of all output files
    List<File>                      outputFileList               = null;

    List<InternalOutputArtifact>    internalOutputArtifactsList  = null;

    /**
     * Simple utility method to enclose a filename in single quotes. This returns the canonical name of the file as a qutoed String. According to the ProGuard
     * docs, all names with special characters like spaces and parentheses must be quoted with single or double quotes. If for any reason the canonical name
     * cannot be determine, it uses the absolute name instead
     * 
     * @param file
     * @return the canonical or absolute filename as a String enclosed in single quotes
     */
    protected static final String returnQuotedFilename(File file) {
        try {
            return "'" + file.getCanonicalPath() + "'";
        } catch (IOException e) {
            return "'" + file.getAbsolutePath() + "'";
        }
    }

    /**
     * Prepares the ProGuard input files
     * 
     * @throws MojoFailureException
     * @throws MojoExecutionException
     */
    private void prepareInputs() throws MojoFailureException, MojoExecutionException {
        log.info("Preparing ProGuard input parameters");

        // Make sure we have a proper input file
        File inJarFile = resolveAbsoluteFile(inputFile, buildDirectory);
        log.info("Primary input: " + inJarFile);
        log.debug("Packaging: " + mavenProject.getPackaging());
        if (!inJarFile.exists()) {
            if (injarNotExistsSkip) {
                log.info("Skipping ProGuard processing because 'inputFile' does not exist");
                return;
            }
            throw new MojoFailureException("Cannot find file " + inJarFile);
        }

        // Process main inputFile
        if (inJarFile.exists()) {
            StringBuffer filter = new StringBuffer(returnQuotedFilename(inJarFile));
            if (excludeManifests || excludeMavenDescriptor || (inputFileFilter != null)) {
                filter.append("(");
                if (excludeManifests)
                    filter.append("!META-INF/MANIFEST.MF");
                if (excludeMavenDescriptor) {
                    if (excludeManifests)
                        filter.append(",");
                    filter.append("!META-INF/maven/**");
                }
                if (inputFileFilter != null) {
                    if (excludeManifests || excludeMavenDescriptor)
                        filter.append(',');
                    filter.append(inputFileFilter);
                }
                filter.append(")");
            }
            args.add(new Option("injars", filter.toString()));
            inputFileList.add(inJarFile);
            log.info("Primary input: " + filter);
        } else
            log.warn("Input does not exist: " + inJarFile);

        // Process additional input artifacts
        if (inputArtifacts != null) {
            for (String str : inputArtifacts) {
                log.debug("Looking for input artifact: " + str);
                Artifact artifact = projectArtifactMap.get(str);
                if (artifact == null)
                    throw new MojoExecutionException("No artifact was found matching " + str + ", please update your project dependencies");
                File f = getFileForArtifact(artifact);
                inputFileList.add(f);
                addInputJar(f.getAbsolutePath());
            }
        }

        // Process additional input paths
        if (inputJarPaths != null) {
            for (String next : inputJarPaths)
                addInputJar(next);
        }
    }

    /**
     * Prepares the ProGuard libraries parameters
     * 
     * @throws MojoExecutionException
     */
    private void prepareLibraries() throws MojoExecutionException {
        log.info("Preparing ProGuard library parameters");

        // More sanity checks - make sure any excluded libraryjar definition is in fact a dependency
        if (excludeLibraryArtifacts != null) {
            for (String excluded : excludeLibraryArtifacts) {
                boolean found = false;
                log.debug("Comparing excluded jar " + excluded);
                for (Artifact artifact : mavenProject.getArtifacts()) {
                    if ((artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion()).equals(excluded)) {
                        found = true;
                        log.debug(excluded + " == " + artifact.toString());
                        break;
                    } else
                        log.debug(excluded + " != " + artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion());
                }
                if (!found)
                    throw new MojoExecutionException("Excluded library " + excluded + " is not a resolved project dependency");
            }
        }

        // Include maven dependencies
        if (includeDependencies) {
            for (Artifact artifact : mavenProject.getArtifacts()) {
                log.debug("Processing dependency " + artifact.getId());
                if (inputArtifacts != null && inputArtifacts.contains(artifact.getGroupId() + ":" + artifact.getArtifactId())) {
                    log.info("Skipping " + artifact.getId() + " as a libraryjar since it is already an included dependency");
                    continue;
                }

                // TODO classifier?
                if ((excludeLibraryArtifacts != null) && (excludeLibraryArtifacts.contains(artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getBaseVersion()))) {
                    log.info("Skipping " + excludeLibraryArtifacts + " as a libraryjar since it is on the exclude list");
                    continue;
                }

                log.info("Adding dependent library: " + artifact.getId());
                File file = getFileForArtifact(artifact);
                args.add(new Option("libraryjars", returnQuotedFilename(file)));
            }
        }

        // Process additional libraryJar paths
        if (libraryJarPaths != null) {
            for (String nextLibJar : libraryJarPaths) {
                String path = returnQuotedFilename(new File(nextLibJar));
                args.add(new Option("libraryjars", path));
            }
        }

        // Process additional artifactLibraryJars
        if (libraryArtifacts != null) {
            for (String nextArtifactLibraryJar : libraryArtifacts) {
                File artifactFile = getFileForArtifact(nextArtifactLibraryJar);
                String path = returnQuotedFilename(artifactFile);
                args.add(new Option("libraryjars", path));
            }
        }

        // Process the default java runtime jar
        if (includeJreRuntimeJar) {
            String path = returnQuotedFilename(new File(includedJreRuntimeJar));
            log.info("Using default runtime jar: " + path);
            args.add(new Option("libraryjars", path));
        }
    }

    /**
     * Prepare other ProGuard options
     */
    private void prepareOtherOptions() {
        // Add include file if specified
        if (ignoreIncludeFile)
            log.info("Ignoring includeFile");
        else if (proguardIncludeFile != null) {
            File pgIncludeFile = resolveAbsoluteFile(proguardIncludeFile, buildDirectory);
            if (pgIncludeFile.exists() && pgIncludeFile.canRead()) {
                log.info("Including proguardInclude file: " + pgIncludeFile.getAbsolutePath());
                args.add(new Option("include", returnQuotedFilename(pgIncludeFile)));
            } else {
                log.info("proguardIncludeFile could not be read: " + proguardIncludeFile);
            }
        }

        // Obfuscate option
        if (!obfuscate)
            args.add(new Option("dontobfuscate"));

        // Shrink option
        if (!shrink)
            args.add(new Option("dontshrink"));

        // DontWarn option
        if (dontwarn)
            args.add(new Option("dontwarn"));

        // PrintMapping options
        if (printMapping)
            args.add(new Option("printmapping", returnQuotedFilename(resolveAbsoluteFile(printMappingFile, proguardOutputDirectory))));

        // PrintSeeds options
        if (printSeeds)
            args.add(new Option("printseeds", returnQuotedFilename(resolveAbsoluteFile(printSeedsFile, proguardOutputDirectory))));

        // Propagate loglevel
        if (log.isDebugEnabled())
            args.add(new Option("verbose"));

        // Pass along other miscellaneous options
        if (options != null) {
            for (String key : options.keySet())
                args.add(new Option(key, options.get(key)));
        }
    }

    /**
     * Prepare the ProGuard output area and parameters
     * 
     * @throws MojoFailureException
     * @throws MojoExecutionException
     */
    private void prepareOutput() throws MojoFailureException, MojoExecutionException {
        // Make sure ProGuard output folder exists
        if (!proguardOutputDirectory.exists()) {
            log.debug("Creating output directory " + proguardOutputDirectory);
            if (!proguardOutputDirectory.mkdir())
                throw new MojoExecutionException("Failed to create output directory: " + proguardOutputDirectory);
        }
        if (!proguardOutputDirectory.isDirectory() || !proguardOutputDirectory.canWrite())
            throw new MojoExecutionException("Output directory cannot be written to: " + proguardOutputDirectory);

        // Create the internal OutputArtifacts list from the plugin parameter
        internalOutputArtifactsList = new ArrayList<InternalOutputArtifact>();
        if (outputArtifacts != null)
            for (OutputArtifact data : outputArtifacts)
                internalOutputArtifactsList.add(new InternalOutputArtifact(data));

        // Use the default output artifact if no outputs are provided
        if (internalOutputArtifactsList.size() == 0) {
            InternalOutputArtifact o = new InternalOutputArtifact();
            internalOutputArtifactsList.add(o);
            log.info("No output artifacts were specified, so setting output file to " + o.getFile());
        }

        // Go through all of the output files and back up any existing files we need to preserve
        for (InternalOutputArtifact out : internalOutputArtifactsList) {
            log.debug("Processing output artifact " + out);
            File outJarFile = out.getFile();
            if (outJarFile != null)
                outJarFile = resolveAbsoluteFile(out.getFile().toString(), buildDirectory);
            else {
                log.error("Could not determine file to use for " + out + ", ignoring");
                continue;
            }
            log.debug("Preparing output file " + outJarFile);

            // Check if our input file contains this same output file
            if (!inputFileList.contains(outJarFile)) {
                // We aren't overwriting the input file so just check if the output file exists and delete
                if (!test && outJarFile.exists() && (!deleteFileOrDirectory(outJarFile)))
                    throw new MojoFailureException("Cannot delete existing file " + outJarFile);
            } else {
                // Writing back to our input file/folder - in this case we must back up the input file first
                File backupFile = new File(buildDirectory, FilenameUtils.getBaseName(outJarFile.getName()) + "_proguard_base." + FilenameUtils.getExtension(outJarFile.getName()));
                log.info("Backing up existing file " + outJarFile.getAbsolutePath() + " to " + backupFile.getAbsolutePath());
                if (backupFile.exists() && !test) {
                    if (!deleteFileOrDirectory(backupFile))
                        throw new MojoFailureException("Cannot delete existing backup file " + backupFile);
                }

                // Rename the input file
                if (!test && outJarFile.exists()) {
                    log.debug("Renaming " + outJarFile + " to " + backupFile);
                    File tempfile = new File(outJarFile.getAbsolutePath());
                    if (!tempfile.renameTo(backupFile))
                        throw new MojoFailureException("Cannot rename " + tempfile + " to " + backupFile);
                    inputFileList.remove(outJarFile);
                    inputFileList.add(backupFile);
                }
            }
            
            // Finally, add the outjar parameter
            args.add(new Option("outjars", outJarFile.getAbsolutePath()));
        }
    }

    /**
     * Main execution method
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        // Log and sanity checks
        Log log = getLog();
        if (skip) {
            log.info("Bypassing ProGuard plug-in because proguard.skip is set to 'true'");
            return;
        }

        // Initialize instance variables
        args = new ArrayList<Option>(); // The ProGuard arguments list
        projectArtifactMap = ProguardMojo.createArtifactMap(mavenProject.getArtifacts());
        inputFileList = new ArrayList<File>();

        // Get ready...
        prepareInputs();
        prepareLibraries();
        prepareOtherOptions();
        prepareOutput();

        // Do it!
        log.info("Launching " + ProGuard.class.getCanonicalName() + " " + args.toString());
        if (test) {
            log.info("This is just a test - no action taken");
            return;
        }
        launchProguard(args);
        log.info("ProGuard completed without exceptions");

        // Attach new artifacts to project
        if (!dontattach) {
            for (InternalOutputArtifact o : internalOutputArtifactsList) {
                if (!test && o.isAttach() && !inputFileList.contains(o.getFile())) {
                    log.info("Attaching resulting artifact to project: " + o.getFile());
                    log.debug("Attaching artifact with type " + o.getType() + " and classifier " + o.getClassifier());
                    mavenProjectHelper.attachArtifact(mavenProject, o.getType(), o.getClassifier(), o.getFile());
                }
            }

            if (printMapping && printMappingAttachAsArtifact) {
                log.info("Attaching printMapping output to project: " + printMappingFile);
                mavenProjectHelper.attachArtifact(mavenProject, FilenameUtils.getExtension(printMappingFile), defaultOutputArtifactClassifier,
                        resolveAbsoluteFile(printMappingFile, proguardOutputDirectory));
            }

            if (printSeeds && printSeedsAttachAsArtifact) {
                log.info("Attaching printSeeds output to project: " + printSeedsFile);
                mavenProjectHelper.attachArtifact(mavenProject, FilenameUtils.getExtension(printSeedsFile), defaultOutputArtifactClassifier,
                        resolveAbsoluteFile(printSeedsFile, proguardOutputDirectory));
            }
        } else
            log.debug("dontattach = true, no attachments performed");
    }

    /**
     * Utility method to generate a String key for the provided artifact of the form <code>&lt;groupid&gt;:&lt;artifactid&gt;[:&lt;classifier&gt;]</code>
     * 
     * @param artifact
     * @return
     */
    protected static String getClassifiedVersionlessKey(Artifact artifact) {
        StringBuffer sb = new StringBuffer();
        sb.append(artifact.getGroupId()).append(':').append(artifact.getArtifactId());
        if (artifact.getClassifier() != null)
            sb.append(':').append(artifact.getClassifier());
        return sb.toString();
    }

    /**
     * Utility method to create a map of all project artifacts using <code>&lt;groupid&gt;:&lt;artifactid&gt;[:&lt;classifier&gt;]</code> as the key. This
     * differs from the MavenProject.getArtifactMap method which overlooks the classifier when creating keys
     * 
     * @param artifacts
     * @return
     */
    protected static Map<String, Artifact> createArtifactMap(Set<Artifact> artifacts) {
        Map<String, Artifact> map = new LinkedHashMap<String, Artifact>();
        for (Artifact artifact : artifacts)
            map.put(getClassifiedVersionlessKey(artifact), artifact);

        return map;
    }

    /**
     * Launches the ProGuard task
     * 
     * @param options
     * @throws MojoExecutionException
     */
    protected void launchProguard(List<Option> options) throws MojoExecutionException {
        getLog().info(ProGuard.VERSION);

        if (options == null || options.size() == 0) {
            getLog().error("Must specify 1 or more arguments");
            return;
        }

        // Create the default options.
        Configuration configuration = new Configuration();

        List<String> argsStr = new ArrayList<String>();
        for (Option option : args)
            argsStr.add(option.toString());

        try {
            // Parse the options specified in the command line arguments.
            ConfigurationParser parser = new ConfigurationParser(argsStr.toArray(new String[] {}), System.getProperties());
            try {
                parser.parse(configuration);
            } finally {
                parser.close();
            }

            // Execute ProGuard with these options.
            new ProGuard(configuration).execute();
        } catch (Exception e) {
            throw new MojoExecutionException("ProGuard threw an exception", e);
        }
    }

    /**
     * Utility method used to delete the output file or folder in preparation for running the task
     * 
     * @param path
     * @return true if and only if the file or directory is successfully deleted; false otherwise
     * @throws MojoFailureException
     */
    protected boolean deleteFileOrDirectory(File path) throws MojoFailureException {
        log.debug("Attempting to delete " + path);
        if (path.isDirectory()) {
            File[] files = path.listFiles();
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    if (!deleteFileOrDirectory(files[i]))
                        throw new MojoFailureException("Cannot delete director " + files[i]);
                } else if (!files[i].delete())
                    throw new MojoFailureException("Cannot delete file " + files[i]);
            }
            return path.delete();
        } else
            return path.delete();
    }

    /**
     * Utility method to return a <code>java.io.File</code> object for the provided Maven <code>Artifact</code> object. It will check to see if the Artifact is
     * a reference to another <code>MavenProject</code>.
     * 
     * @param artifact
     * @return File
     * @throws MojoExecutionException
     */
    protected File getFileForArtifact(Artifact artifact) throws MojoExecutionException {
        org.eclipse.aether.artifact.Artifact aetherartifact = null;
        try {
            aetherartifact = new DefaultArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getClassifier(), artifact.getType(), artifact.getVersion());
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        return getFileForArtifact(aetherartifact);
    }

    /**
     * Utility method to return a <code>java.io.File</code> object for the provided coordinates. It will check to see if the Artifact is a reference to another
     * <code>MavenProject</code>.
     * 
     * @param coordinates
     * @return File
     * @throws MojoExecutionException
     */
    protected File getFileForArtifact(String coordinates) throws MojoExecutionException {
        org.eclipse.aether.artifact.Artifact artifact = null;
        try {
            artifact = new DefaultArtifact(coordinates);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        return getFileForArtifact(artifact);
    }

    /**
     * Utility method to return a <code>java.io.File</code> object for the provided Aether artifact. It will check to see if the Artifact is a reference to
     * another <code>MavenProject</code>.
     * 
     * @param aetherArtifact
     * @return File
     * @throws MojoExecutionException
     */
    protected File getFileForArtifact(org.eclipse.aether.artifact.Artifact aetherArtifact) throws MojoExecutionException {
        String refId = aetherArtifact.getGroupId() + ":" + aetherArtifact.getArtifactId();
        MavenProject project = mavenProject.getProjectReferences().get(refId);

        // If we have a classifier or there is no child project, return the associated file
        if ((aetherArtifact.getClassifier() != null) || (project == null)) {
            File file = aetherArtifact.getFile();
            if ((file == null) || (!file.exists()))
                return resolveArtifact(aetherArtifact);
            return file;
        }

        // The artifact references another project, so return that project's output directory
        return new File(project.getBuild().getOutputDirectory());
    }

    /**
     * Utility method to resolve an artifact. Adapted from
     * http://git.eclipse.org/c/aether/aether-demo.git/tree/aether-demo-maven-plugin/src/main/java/org/eclipse/aether/examples/maven/ResolveArtifactMojo.java
     * 
     * @param artifactCoordinates
     * @throws MojoExecutionException
     */
    protected File resolveArtifact(org.eclipse.aether.artifact.Artifact artifact) throws MojoExecutionException {
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(remoteRepositories);

        getLog().info("Attempting to resolving artifact " + artifact + " from " + remoteRepositories);

        ArtifactResult result;
        try {
            result = repoSystem.resolveArtifact(repoSession, request);
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        getLog().info("Successfully resolved artifact " + artifact + " to " + result.getArtifact().getFile() + " from " + result.getRepository());
        return result.getArtifact().getFile();
    }

    /**
     * Utility method to test the filename to see if it is absolute or not. If it is absolute, it is returned. If not, it is appended to the base and returned.
     * 
     * @param name
     * @param base
     * @return File
     */
    protected File resolveAbsoluteFile(String name, File base) {
        File tempFile = new File(name);
        if (!tempFile.isAbsolute())
            tempFile = new File(base, name);
        return tempFile;
    }

    /**
     * Utility method to add the provided input jar filename to the ProGuard arguments list as an <em>injars</em> parameter. It dynamically adds filters for
     * manifests and maven descriptors if configured to do so
     * 
     * @param inJarName
     */
    protected void addInputJar(String inJarName) {
        // TODO handle if there is a filter already specified
        String nextInJarPath = returnQuotedFilename(resolveAbsoluteFile(inJarName, buildDirectory));
        StringBuffer filter = new StringBuffer(nextInJarPath);
        if (excludeManifests || excludeMavenDescriptor) {
            filter.append("(");
            if (excludeManifests)
                filter.append("!META-INF/MANIFEST.MF");
            if (excludeMavenDescriptor) {
                if (excludeManifests)
                    filter.append(",");
                filter.append("!META-INF/maven/**");
            }
            filter.append(")");
        }
        args.add(new Option("injars", filter.toString()));
    }
}
