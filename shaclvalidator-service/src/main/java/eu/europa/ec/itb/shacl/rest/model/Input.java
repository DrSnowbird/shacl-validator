package eu.europa.ec.itb.shacl.rest.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import eu.europa.ec.itb.shacl.SparqlQueryConfig;
import eu.europa.ec.itb.validation.commons.FileContent;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@ApiModel(description = "The content and metadata specific to input content that is to be validated.")
public class Input {

    @ApiModelProperty(notes = "The RDF content to validate, provided as a normal string, a URL, or a BASE64-encoded string. Either this must be provided or a SPARQL query (contentQuery).")
	private String contentToValidate;
    @ApiModelProperty(notes = "The mime type of the provided RDF content (e.g. \"application/rdf+xml\", \"application/ld+json\", \"text/turtle\"). If not provided the type is determined from the provided content (if possible).")
	private String contentSyntax;
    @ApiModelProperty(notes = "The way in which to interpret the contentToValidate. If not provided, the method will be determined from the contentToValidate value.", allowableValues = FileContent.embedding_STRING+","+FileContent.embedding_URL+","+FileContent.embedding_BASE64)
	private String embeddingMethod;
    @ApiModelProperty(notes = "The type of validation to perform (e.g. the profile to apply or the version to validate against). This can be skipped if a single validation type is supported by the validator. Otherwise, if multiple are supported, the service should fail with an error.")
	private String validationType;
    @ApiModelProperty(notes = "The mime type for the validation report syntax (e.g. \"application/ld+json\", \"application/rdf+xml\", \"text/turtle\", \"application/n-triples\"). If none is provided \"application/rdf+xml\" is considered as the default, unless a different syntax is configured for the domain in question.")
	private String reportSyntax;
    @ApiModelProperty(notes = "Any shapes to consider that are externally provided (i.e. provided at the time of the call).")
	private List<RuleSet> externalRules;
    @ApiModelProperty(notes = "If owl:Imports should be loaded from the RDF content. This can be skipped if defined in the configuration. If not provided, the decision is determined from the configuration for the domain in question.")
	private Boolean loadImports;
	@ApiModelProperty(notes = "The SPARQL endpoint URI.")
	private String contentQueryEndpoint;
	@ApiModelProperty(notes = "The SPARQL query to execute.")
	private String contentQuery;
	@ApiModelProperty(notes = "Username to access the SPARQL endpoint.")
	private String contentQueryUsername;
	@ApiModelProperty(notes = "Password to access the SPARQL endpoint.")
	private String contentQueryPassword;

	public String getContentToValidate() { return this.contentToValidate; }
	
	public String getEmbeddingMethod() { return this.embeddingMethod; }
	
	public String getValidationType() { return this.validationType; }
	
	public String getReportSyntax() { return this.reportSyntax; }
	
	public String getContentSyntax() { return this.contentSyntax; }
	
	public List<RuleSet> getExternalRules(){ return this.externalRules; }
	
	public RuleSet getExternalRules(int value) { return this.externalRules.get(value); }
	
	public Boolean isLoadImports(){ return this.loadImports; }
	
	public void setContentToValidate(String contentToValidate) {
		this.contentToValidate = contentToValidate;
	}

	public void setContentSyntax(String contentSyntax) {
		this.contentSyntax = contentSyntax;
	}

	public void setEmbeddingMethod(String embeddingMethod) {
		this.embeddingMethod = embeddingMethod;
	}

	public void setValidationType(String validationType) {
		this.validationType = validationType;
	}

	public void setReportSyntax(String reportSyntax) {
		this.reportSyntax = reportSyntax;
	}

	public void setExternalRules(List<RuleSet> externalRules) {
		this.externalRules = externalRules;
	}

	public void setLoadImports(Boolean loadImports) {
		this.loadImports = loadImports;
	}

	public String getContentQueryEndpoint() {
		return contentQueryEndpoint;
	}

	public void setContentQueryEndpoint(String contentQueryEndpoint) {
		this.contentQueryEndpoint = contentQueryEndpoint;
	}

	public String getContentQuery() {
		return contentQuery;
	}

	public void setContentQuery(String contentQuery) {
		this.contentQuery = contentQuery;
	}

	public String getContentQueryUsername() {
		return contentQueryUsername;
	}

	public void setContentQueryUsername(String contentQueryUsername) {
		this.contentQueryUsername = contentQueryUsername;
	}

	public String getContentQueryPassword() {
		return contentQueryPassword;
	}

	public void setContentQueryPassword(String contentQueryPassword) {
		this.contentQueryPassword = contentQueryPassword;
	}

	public SparqlQueryConfig parseQueryConfig() {
		SparqlQueryConfig config = null;
		if (contentQuery != null || contentQueryEndpoint != null || contentQueryPassword != null || contentQueryUsername != null) {
			config = new SparqlQueryConfig(contentQueryEndpoint, contentQuery, contentQueryUsername, contentQueryPassword, contentSyntax);
		}
		return config;
	}
}
