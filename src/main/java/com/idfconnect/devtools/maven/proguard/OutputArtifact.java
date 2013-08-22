package com.idfconnect.devtools.maven.proguard;


/**
 * A simple JavaBean for holding output artifact parameters
 * 
 * @author Richard Sand
 */
public class OutputArtifact {
    private String  groupId = null;
    private String  artifactId = null;
    private String  version = null;
    private String  type = null;
    private String  classifier = null;
    private String  file = null;
    private boolean attach = true;
    
    public OutputArtifact() {
        
    }

    public OutputArtifact(String groupId, String artifactId, String version, String type, String classifier, String file, boolean attach) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.type = type;
        this.classifier = classifier;
        this.file = file;
        this.attach = attach;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getClassifier() {
        return classifier;
    }

    public void setClassifier(String classifier) {
        this.classifier = classifier;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public boolean isAttach() {
        return attach;
    }

    public void setAttach(boolean attach) {
        this.attach = attach;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("OutputArtifact [");
        if (groupId != null) {
            builder.append("groupId=");
            builder.append(groupId);
            builder.append(", ");
        }
        if (artifactId != null) {
            builder.append("artifactId=");
            builder.append(artifactId);
            builder.append(", ");
        }
        if (version != null) {
            builder.append("version=");
            builder.append(version);
            builder.append(", ");
        }
        if (type != null) {
            builder.append("type=");
            builder.append(type);
            builder.append(", ");
        }
        if (classifier != null) {
            builder.append("classifier=");
            builder.append(classifier);
            builder.append(", ");
        }
        if (file != null) {
            builder.append("file=");
            builder.append(file);
            builder.append(", ");
        }
        builder.append("attach=");
        builder.append(attach);
        builder.append("]");
        return builder.toString();
    }
}