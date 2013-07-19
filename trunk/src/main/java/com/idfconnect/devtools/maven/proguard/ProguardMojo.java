package com.idfconnect.devtools.maven.proguard;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.ProGuard;

/**
 * A Maven 3.0 plug-in for using ProGuard to obfuscate project artifacts
 * 
 * @author Richard Sand
 */
@Mojo(name = "obfuscate", defaultPhase = LifecyclePhase.PROCESS_CLASSES, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public final class ProguardMojo extends AbstractMojo {
    /**
     * Internal class for holding a single ProGuard command-line option
     */
    class Option {
        String name  = null;
        String value = null;

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
     * Set this to 'true' to bypass ProGuard processing entirely.
     * 
     */
    @Parameter(defaultValue = "false", property = "proguard.skip")
    private boolean                                           skip                 = false;

    /**
     * Set this to 'true' to test the plug-in without launching ProGuard. This will simply show you how the plug-in builds the ProGuard invocation arguments
     * 
     */
    @Parameter(defaultValue = "false", property = "proguard.test")
    private boolean                                           test                 = false;

    /**
     * Base directory for all operations. Defaults to <code>${project.build.directory}</code>.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "proguard.builddir", required = true)
    private File                                              buildDirectory;

    /**
     * Output directory for ProGuard files, such as the mapping file. Defaults to <code>${project.build.directory}/proguard</code>.
     */
    @Parameter(defaultValue = "${project.build.directory}/proguard", property = "proguard.output", required = true)
    private File                                              proguardOutputDirectory;

    /**
     * Set this to 'true' to delete the original input files after obfuscation has completed. The default value is 'false' which means the files will be kept.
     * Note that this will not apply to the primary <em>inputFile</em> if the output overwrites the same file, or if the input file is a folder (e.g.
     * target/classes)
     */
    @Parameter(defaultValue = "false", property = "proguard.deleteinput")
    private boolean                                           deleteInputFiles     = false;

    /**
     * Includes additional ProGuard configuration options from the provided file. This defaults to
     * <code>${basedir}/src/main/config/${project.artifactId}-maven.pro</code>. If no such file exists, the parameter is ignored. This behavior can be disabled
     * by the parameter <em>ignoreIncludeFile</em>
     */
    @Parameter(defaultValue = "${basedir}/src/main/config/${project.artifactId}-maven.pro")
    private String                                            proguardIncludeFile;

    /**
     * Set this to 'true' to disable the parameter <em>proguardInclude</em>
     */
    @Parameter(defaultValue = "false", property = "proguard.ignoreincludefile")
    private boolean                                           ignoreIncludeFile    = false;

    /**
     * Other arbitrary ProGuard configuration options
     */
    @Parameter
    private Map<String, String>                               options;

    /**
     * Specifies to obfuscate the input class files. Setting this to <em>false</em> sets the ProGuard option <code>-dontobfuscate</em>
     */
    @Parameter(defaultValue = "true")
    private boolean                                           obfuscate            = true;

    /**
     * Specifies not to shrink the input class files. Setting this to <em>false</em> sets the ProGuard option <code>-dontshrink</em>
     */
    @Parameter(defaultValue = "true")
    private boolean                                           shrink               = true;

    /**
     * Tells ProGuard not to error out if there are unresolved references to classes or interfaces. Defaults to false
     */
    @Parameter(defaultValue = "false")
    private boolean                                           dontwarn               = false;
    
    /**
     * Specifies that project compile dependencies should be automatically added as <em>libraryjars</em>
     */
    @Parameter(defaultValue = "true")
    private boolean                                           includeDependencies  = true;

    /**
     * Additional <em>injars</em>
     */
    @Parameter
    private List<String>                                      inJars;

    /**
     * Automatically exclude via ProGuard filter the manifests from any <em>injars</em>. Note that if this is set to false, such a filter may still be included
     * explicitly on any <em>injar</em> entry
     */
    @Parameter(defaultValue = "true")
    private boolean                                           excludeManifests     = true;

    /**
     * Automatically adds the java runtime jar <code>${java.home}/lib/rt.jar</code> to the ProGuard <em>libraryjars</em>. Defaults to true.
     */
    @Parameter(defaultValue = "true")
    private boolean                                           includeJreRuntimeJar = true;
    @Parameter(defaultValue = "${java.home}/lib/rt.jar", readonly = true)
    private String                                            includedJreRuntimeJar;

    /**
     * Additional external <em>libraryjars</em>, e.g. ${java.home}/lib/rt.jar.
     */
    @Parameter
    private List<String>                                      libraryJars;

    /**
     * Additional artifacts as <em>libraryjars</em>, e.g. javax.servlet-api:javax.servlet-ap:3.0.1.
     */
    @Parameter
    private List<String>                                      artifactLibraryJars;

    /**
     * This parameter will generate additional <em>injar</em> input entries to ProGuard from the project artifacts. Set the artifact names in String form, e.g.
     * <code>com.idfconnect.someproject:SomeLibrary</code> to pull it from the project dependencies
     */
    @Parameter
    private List<String>                                      inputArtifacts;

    /**
     * Explicitly sets the ProGuard <em>outjars</em> parameter. If not specified, the default will be used
     */
    @Parameter
    private List<String>                                      outJars;

    /**
     * Specifies the input file name (e.g. classes folder, jar, war, ear, zip, etc.) to be processed. This defaults to the typical output of the packaging
     * phase, which is <code>${project.build.finalName}.${project.packaging}</code>. If you are obfuscating before the packaging phase, you will typically want
     * to set this to <code>${project.build.outputDirectory}</code> to indicate the classes directory. If a relative path is specified, it will be relative to
     * the base directory.
     */
    @Parameter(defaultValue = "${project.build.finalName}.${project.packaging}")
    private String                                            inputFile;

    /**
     * Specifies the ProGuard-syntax input filter to apply to the input file. Note that this does only applies to the input file. It does not apply to other
     * jars specified via <em>inputArtifacts</em> or <em>inputJars</em>. Filters for other <em>inJars</em> can be appended to the ends of those individual
     * elements.
     */
    @Parameter
    private String                                            inputFileFilter;

    /**
     * Set this to 'true' to bypass ProGuard processing when injars do not exists
     */
    @Parameter(defaultValue = "false")
    private boolean                                           injarNotExistsSkip   = false;

    /**
     * Specifies the names of the output archive file. If <code>attach=true</code> then this value ignored and the name is constructed based on the classifier.
     * If no value is specified, it will default to the default to <code>${project.build.finalName}.${project.packaging}</code>, or to
     * <code>${project.build.finalName}-&lt;artifactClassifier&gt;.${project.packaging}</code> if a classifier is specified.
     */
    @Parameter
    private String                                            outputFile;

    /**
     * Specifies whether or not to attach the created artifact to the project
     * 
     */
    @Parameter(defaultValue = "true")
    private boolean                                           attach               = true;

    /**
     * Specifies the output artifact type. Defaults to <em>${project.packaging}</em>
     * 
     */
    @Parameter(defaultValue = "${project.packaging}")
    private String                                            outputArtifactType;

    /**
     * Specifies the output artifact Classifier. The default value is "small".
     */
    @Parameter(defaultValue = "small")
    private String                                            outputArtifactClassifier;

    /**
     * Indicates whether the <em>outputArtifactClassifier</em> should be appended to the output artifact's final name. Default value is true.
     */
    @Parameter(defaultValue = "true")
    private boolean                                           appendClassifierToOutput;

    /**
     * Indicates whether <em>printmapping</em> should be specified. Defaults to true.
     */
    @Parameter(defaultValue = "true")
    private boolean                                           printMapping         = true;

    /**
     * Filename to use with <em>printmapping</em>. Defaults to <em>proguard.map</em>
     */
    @Parameter(defaultValue = "proguard.map")
    private String                                            printMappingFile     = "proguard.map";

    /**
     * Indicates whether <em>printseeds</em> should be specified. Defaults to false.
     */
    @Parameter(defaultValue = "false")
    private boolean                                           printSeeds           = false;

    /**
     * Filename to use with <em>printseeds</em>. Defaults to <em>proguard.seeds</em>
     */
    @Parameter(defaultValue = "proguard.seeds")
    private String                                            printSeedsFile       = "proguard.seeds";

    /**
     * Set to false to include META-INF/maven/**
     */
    @Parameter(defaultValue = "true")
    private boolean                                           excludeMavenDescriptor;

    /**
     * The Maven project reference where the plug-in is being executed. This value is read-only and is populated by Maven
     * 
     */
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject                                      mavenProject;

    /**
     * The Maven project helper component
     * 
     */
    @Component
    private MavenProjectHelper                                mavenProjectHelper;

    /**
     * Default factory for creating artifact objects
     */
    //@Component
    //private DefaultArtifactFactory factory;
    
    /**
     * The local repository where the artifacts are located
     */
    //@Component
    //private ArtifactRepository localRepository;
    
    /**
     * The remote repositories where artifacts are located
     */
    //@Component
    //private List<RemoteRepository> remoteRepositories;
    
    
    
    // //
    // Other instance variables
    // //

    // The ProGuard arguments list
    private List<Option>                                      args                 = null;

    // The project's artifact map
    private Map<String, Artifact>                             projectArtifactMap   = null;

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
     * Main execution method
     */
    public void execute() throws MojoExecutionException, MojoFailureException {
        // Log and sanity checks
        Log log = getLog();
        if (skip) {
            log.info("Bypassing ProGuard plug-in because proguard.skip is set to 'true'");
            return;
        }
        if (appendClassifierToOutput && ((outputArtifactClassifier == null) || ("".equals(outputArtifactClassifier.length()))))
            throw new MojoExecutionException("AppendClassifier was set to true but no classifier value was provided");

        // Initialize instance variables
        args = new ArrayList<Option>(); // The ProGuard arguments list
        projectArtifactMap = mavenProject.getArtifactMap(); // Resolved artifacts
        File outJarFile = null; // output File
        boolean sameArtifact = true; // indicates that the ProGuard output is the same as the input artifact

        // Make sure we have a proper input file
        File inJarFile = getAbsoluteFile(inputFile, buildDirectory);
        log.info("Primary input: " + inJarFile);
        log.debug("Packaging: " + mavenProject.getPackaging());
        if (!inJarFile.exists()) {
            if (injarNotExistsSkip) {
                log.info("Skipping ProGuard processing because 'inputFile' does not exist");
                return;
            }
            throw new MojoFailureException("Cannot find file " + inJarFile);
        }

        // Compute the output file if not provided OR if attach=true
        if (attach || (outputFile == null)) {
            StringBuffer outputFileBuf = new StringBuffer(FilenameUtils.getBaseName(inputFile));
            if (appendClassifierToOutput)
                outputFileBuf.append('-').append(outputArtifactClassifier);
            outputFileBuf.append('.').append(outputArtifactType);
            outputFile = outputFileBuf.toString();
            log.info("Setting output file to " + outputFile);
        }

        if ((outputFile != null) && (!outputFile.equals(inputFile))) {
            sameArtifact = false;
            outJarFile = (new File(buildDirectory, outputFile)).getAbsoluteFile();
            log.debug("Using output file " + outJarFile);
            if (!test && outJarFile.exists() && (!deleteFileOrDirectory(outJarFile)))
                throw new MojoFailureException("Cannot delete existing file " + outJarFile);
        } else {
            // Writing back to our input file/folder - in this case we must back up the input file first
            outJarFile = inJarFile.getAbsoluteFile();
            File backupFile = new File(buildDirectory, FilenameUtils.getBaseName(inputFile) + "_proguard_base" + ((!inJarFile.isDirectory() ? ".jar" : "")));
            log.info("Backing up existing file " + outJarFile.getAbsolutePath() + " to " + backupFile.getAbsolutePath());
            if (backupFile.exists() && !test) {
                if (!deleteFileOrDirectory(backupFile))
                    throw new MojoFailureException("Cannot delete existing backup file " + backupFile);
            }

            // Rename the input file
            if (!test && inJarFile.exists() && (!inJarFile.renameTo(backupFile)))
                throw new MojoFailureException("Cannot rename " + inJarFile + " to " + backupFile);
            inJarFile = backupFile;
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
                addInputJar(getFileForArtifact(artifact).getAbsolutePath());
                /*
                 * if (attach && (artifact.getScope().equalsIgnoreCase(Artifact.SCOPE_COMPILE) ||
                 * artifact.getScope().equalsIgnoreCase(Artifact.SCOPE_COMPILE_PLUS_RUNTIME))) { log.info("Changing artifact " + artifact + " to scope " +
                 * Artifact.SCOPE_PROVIDED); if (!test) { artifact.setScope(Artifact.SCOPE_PROVIDED); mavenProject.setArtifact(artifact); } }
                 */
            }
        }

        // Process additional inJars
        if (inJars != null) {
            for (String nextInJar : inJars)
                addInputJar(nextInJar);
        }

        // Include maven dependencies
        if (includeDependencies) {
            for (Artifact artifact : mavenProject.getArtifacts()) {
                log.debug("Processing dependency " + artifact.getId());
                if (inputArtifacts.contains(artifact.getGroupId() + ":" + artifact.getArtifactId())) {
                    log.info("Skipping " + artifact.getId() + " as a libraryjar since it is already an included dependency");
                    continue;
                }

                log.info("Adding dependent library: " + artifact.getId());
                File file = getFileForArtifact(artifact);
                args.add(new Option("libraryjars", returnQuotedFilename(file)));
            }
        }

        // Process additional libraryJars
        if (libraryJars != null) {
            for (String nextLibJar : libraryJars) {
                String path = returnQuotedFilename(new File(nextLibJar));
                args.add(new Option("libraryjars", path));
            }
        }

        // Process additional artifactLibraryJars
/*        
        if (artifactLibraryJars != null) {
            for (String nextArtifactLibraryJar : artifactLibraryJars) {
                Artifact artifact = factory.createArtifact(groupId, artifactId, version, scope, type);
                File artifactFile = artifact.getFile();
                String path = returnQuotedFilename(artifactFile);
                args.add(new Option("libraryjars", path));
            }
        }
*/
        
        // Process the default java runtime jar
        if (includeJreRuntimeJar) {
            String path = returnQuotedFilename(new File(includedJreRuntimeJar));
            log.info("Using default runtime jar: " + path);
            args.add(new Option("libraryjars", path));
        }

        // Process output jars
        args.add(new Option("outjars", returnQuotedFilename(outJarFile)));

        // Add include file if specified
        if (ignoreIncludeFile)
            log.info("Ignoring includeFile");
        else if (proguardIncludeFile != null) {
            File pgIncludeFile = getAbsoluteFile(proguardIncludeFile, buildDirectory);
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

        // Make sure ProGuard output folder exists
        if (!proguardOutputDirectory.exists()) {
            log.debug("Creating output directory " + proguardOutputDirectory);
            if (!proguardOutputDirectory.mkdir())
                throw new MojoExecutionException("Failed to create output directory: " + proguardOutputDirectory);
        }
        if (!proguardOutputDirectory.isDirectory() || !proguardOutputDirectory.canWrite())
            throw new MojoExecutionException("Output directory cannot be written to: " + proguardOutputDirectory);

        // DontWarn option
        if (dontwarn)
            args.add(new Option("dontwarn"));

        // PrintMapping options
        if (printMapping)
            args.add(new Option("printmapping", returnQuotedFilename(getAbsoluteFile(printMappingFile, proguardOutputDirectory))));

        // PrintSeeds options
        if (printSeeds)
            args.add(new Option("printseeds", returnQuotedFilename(getAbsoluteFile(printSeedsFile, proguardOutputDirectory))));

        // Propagate loglevel
        if (log.isDebugEnabled())
            args.add(new Option("verbose"));

        // Pass along other miscellaneous options
        if (options != null) {
            for (String key : options.keySet())
                args.add(new Option(key, options.get(key)));
        }

        // Do it!
        log.info("Launching " + ProGuard.class.getCanonicalName() + " " + args.toString());
        if (test) {
            log.info("This is just a test - no action taken");
            return;
        }
        launchProguard(args);
        log.info("ProGuard completed without exceptions");

        // Black widow the input files we obfuscated, if specified
        if (!test && deleteInputFiles && !sameArtifact && !inJarFile.isDirectory()) {
            log.info("Deleting original input file: " + inJarFile);
            inJarFile.delete();
        }
        if (!test && deleteInputFiles && (inJars != null)) {
            for (String injar : inJars) {
                File f = new File(injar);
                if (f.exists()) {
                    log.info("Deleting input jar: " + injar);
                    f.delete();
                }
            }
        }

        // Attach new artifact to project
        if (!test && attach && !sameArtifact) {
            log.info("Attaching resulting artifact to project: " + outJarFile);
            mavenProjectHelper.attachArtifact(mavenProject, outputArtifactType, (appendClassifierToOutput ? outputArtifactClassifier : null), outJarFile);
        }
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
        List<String> args = new ArrayList<String>();
        for (Option option : options)
            args.add(option.toString());

        // Create the default options.
        Configuration configuration = new Configuration();

        try {
            // Parse the options specified in the command line arguments.
            ConfigurationParser parser = new ConfigurationParser(args.toArray(new String[] {}), System.getProperties());
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

    // TODO replace this
    private static boolean deleteFileOrDirectory(File path) throws MojoFailureException {
        if (path.isDirectory()) {
            File[] files = path.listFiles();
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    if (!deleteFileOrDirectory(files[i])) {
                        throw new MojoFailureException("Cannot delete director " + files[i]);
                    }
                } else {
                    if (!files[i].delete()) {
                        throw new MojoFailureException("Cannot delete file " + files[i]);
                    }
                }
            }
            return path.delete();
        } else {
            return path.delete();
        }
    }

    /**
     * Utility method to return a <code>java.io.File</code> object for the provided <code>Artifact</code> object. It will check to see if the Artifact is a
     * reference to another <code>MavenProject</code>.
     * 
     * @param artifact
     * @return File
     * @throws MojoExecutionException
     */
    protected File getFileForArtifact(Artifact artifact) throws MojoExecutionException {
        String refId = artifact.getGroupId() + ":" + artifact.getArtifactId();
        MavenProject project = mavenProject.getProjectReferences().get(refId);

        // If we have a classifier or there is no child project, return the associated file
        if ((artifact.getClassifier() != null) || (project == null)) {
            File file = artifact.getFile();
            if ((file == null) || (!file.exists()))
                throw new MojoExecutionException("Dependency resolution needed for " + artifact);
            return file;
        }

        // The artifact references another project, so return that project's output directory
        return new File(project.getBuild().getOutputDirectory());
    }

    /**
     * Utility method to test the filename to see if it is absolute or not. If it is absolute, it is returned. If not, it is appended to the base and returned.
     * 
     * @param name
     * @param base
     * @return File
     */
    protected File getAbsoluteFile(String name, File base) {
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
        String nextInJarPath = returnQuotedFilename(getAbsoluteFile(inJarName, buildDirectory));
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
