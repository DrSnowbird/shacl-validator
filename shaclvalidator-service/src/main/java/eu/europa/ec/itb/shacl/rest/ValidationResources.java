package eu.europa.ec.itb.shacl.rest;

import org.apache.jena.rdf.model.Model;

import java.util.Optional;

/**
 * Class used to wrap all resources that drive and result from a validation.
 */
public class ValidationResources {

    private final String inputContent;
    private final Model input;
    private final Model report;
    private final Model shapes;

    /**
     * Constructor.
     *
     * @param inputContent The provided input content (if needed).
     * @param input The final input model that was validated.
     * @param report The produced SHACL validation report.
     * @param shapes The SHACL shapes that were used.
     */
    public ValidationResources(String inputContent, Model input, Model report, Model shapes) {
        this.inputContent = inputContent;
        this.input = input;
        this.report = report;
        this.shapes = shapes;
    }

    /**
     * @return The provided input content (if needed).
     */
    public Optional<String> getInputContent() {
        return Optional.ofNullable(inputContent);
    }

    /**
     * @return The final input model that was validated.
     */
    public Model getInput() {
        return input;
    }

    /**
     * @return The produced SHACL validation report.
     */
    public Model getReport() {
        return report;
    }

    /**
     * @return The SHACL shapes that were used.
     */
    public Model getShapes() {
        return shapes;
    }
}
