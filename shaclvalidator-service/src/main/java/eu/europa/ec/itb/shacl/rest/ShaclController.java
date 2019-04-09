package eu.europa.ec.itb.shacl.rest;

import eu.europa.ec.itb.shacl.ApplicationConfig;
import eu.europa.ec.itb.shacl.DomainConfig;
import eu.europa.ec.itb.shacl.DomainConfigCache;
import eu.europa.ec.itb.shacl.ValidatorChannel;
import eu.europa.ec.itb.shacl.rest.errors.NotFoundException;
import eu.europa.ec.itb.shacl.rest.errors.ValidatorException;
import eu.europa.ec.itb.shacl.rest.model.ApiInfo;
import eu.europa.ec.itb.shacl.rest.model.Input;
import eu.europa.ec.itb.shacl.rest.model.Output;
import eu.europa.ec.itb.shacl.rest.model.RuleSet;
import eu.europa.ec.itb.shacl.validation.FileManager;
import eu.europa.ec.itb.shacl.validation.SHACLValidator;
import io.swagger.annotations.*;
import org.apache.commons.io.FileUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;

/**
 * Simple REST controller to allow an easy way of validating files with the correspondence shapes.
 * 
 * Created by mfontsan on 25/03/2019
 *
 */
@Api(value="/{domain}/api", description = "Operations for the validation of RDF content based on SHACL shapes.")
@RestController
public class ShaclController {

    private static final Logger logger = LoggerFactory.getLogger(ShaclController.class);
    
    @Autowired
    ApplicationContext ctx;
    @Autowired
    ApplicationConfig config;
	@Autowired
	DomainConfigCache domainConfigs;
    @Autowired
	FileManager fileManager;
    @Value("${validator.acceptedHeaderAcceptTypes}")
	Set<String> acceptedHeaderAcceptTypes;
    @Value("${validator.hydraServer}")
	String hydraServer;
	@Value("${validator.hydraRootPath}")
	private String hydraRootPath;

	@ApiOperation(value = "Get API information.", response = ApiInfo.class, notes="Retrieve the supported validation " +
			"types that can be requested when calling this API's validation operations.")
	@ApiResponses({
			@ApiResponse(code = 200, message = "Success", response = ApiInfo.class),
			@ApiResponse(code = 500, message = "Error (If a problem occurred with processing the request)", response = String.class),
			@ApiResponse(code = 404, message = "Not found (for an invalid domain value)", response = String.class)
	})
	@RequestMapping(value = "/{domain}/api/info", method = RequestMethod.GET, consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiInfo info(
			@ApiParam(required = true, name = "domain", value = "A fixed value corresponding to the specific validation domain.")
			@PathVariable("domain") String domain
	) {
		DomainConfig domainConfig = validateDomain(domain);
		return ApiInfo.fromDomainConfig(domainConfig);
	}

	/**
	 * POST service to receive a single RDF instance to validate
	 *
	 * @param domain The domain where the SHACL validator is executed.
	 * @return The result of the SHACL validator.
	 */
	@ApiOperation(value = "Validate one RDF instance.", response = String.class, notes="Validate a single RDF instance. " +
			"The content can be provided either within the request as a BASE64 encoded string or remotely as a URL. The RDF syntax for the input can be " +
			"determined in the request as can the syntax to produce the resulting SHACL validation report.")
	@ApiResponses({
			@ApiResponse(code = 200, message = "Success (for successful validation)", response = String.class),
			@ApiResponse(code = 500, message = "Error (If a problem occurred with processing the request)", response = String.class),
			@ApiResponse(code = 404, message = "Not found (for an invalid domain value)", response = String.class)
	})
	@RequestMapping(value = "/{domain}/api/validate", method = RequestMethod.POST, consumes = {MediaType.APPLICATION_JSON_VALUE, "application/ld+json"}, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<String> validate(
			@ApiParam(required = true, name = "domain", value = "A fixed value corresponding to the specific validation domain.")
			@PathVariable("domain") String domain,
			@ApiParam(required = true, name = "input", value = "The input for the validation (content and metadata for one RDF instance).")
			@RequestBody Input in,
			HttpServletRequest request
	) {
		String shaclResult;
		DomainConfig domainConfig = validateDomain(domain);

		//Start validation of the input file
		File inputFile = null;
		try {
			inputFile = getContentToValidate(in);
			//Execute one single validation
			Model shaclReport = executeValidation(inputFile, in, domainConfig);

			// Consider first the report syntax requested as part of the input properties.
			String reportSyntax = in.getReportSyntax();
			if (reportSyntax == null) {
				// If unspecified consider the first acceptable syntax from the Accept header.
				reportSyntax = getFirstSupportedAcceptHeader(request);
			}
			if (reportSyntax == null) {
				// No syntax specified by client - use the configuration default.
				reportSyntax = domainConfig.getDefaultReportSyntax();
			} else {
				if (!validReportSyntax(reportSyntax)) {
					// The requested syntax is invalid.
					logger.error(ValidatorException.message_parameters, reportSyntax);
					throw new ValidatorException(ValidatorException.message_parameters);
				}
			}

			//Process the result according to content-type
			shaclResult = getShaclReport(shaclReport, reportSyntax);
			HttpHeaders responseHeaders = new HttpHeaders();
			responseHeaders.setContentType(MediaType.parseMediaType(reportSyntax));
			return new ResponseEntity<>(shaclResult, responseHeaders, HttpStatus.CREATED);
		} catch (ValidatorException | NotFoundException e) {
			throw e;
		} catch (Exception e) {
			logger.error("Unexpected error occurred during processing", e);
			throw new ValidatorException(ValidatorException.message_default);
		} finally {
			//Remove temporary files
			fileManager.removeContentToValidate(inputFile);
		}
	}

	private String getFirstSupportedAcceptHeader(HttpServletRequest request) {
		Enumeration<String> headerValues = request.getHeaders(HttpHeaders.ACCEPT);
		while (headerValues.hasMoreElements()) {
			String acceptHeader = headerValues.nextElement();
			for (String acceptableValue: acceptedHeaderAcceptTypes) {
				if (acceptHeader.contains(acceptableValue)) {
					return acceptableValue;
				}
			}
		}
		return null;
	}

	private boolean validReportSyntax(String reportSyntax) {
		Lang lang = RDFLanguages.contentTypeToLang(reportSyntax.toLowerCase());
		return lang != null;
	}

    
    /**
     * POST service to receive multiple RDF instances to validate
     * 
     * @param domain The domain where the SHACL validator is executed. 
     * @return The result of the SHACL validator.
     */
    @ApiOperation(value = "Validate multiple RDF instances.", response = Output[].class, notes="Validate multiple RDF instances. " +
			"The content for each instance can be provided either within the request as a BASE64 encoded string or remotely as a URL. " +
			"The RDF syntax for each input can be determined in the request as can the syntax to produce each resulting SHACL validation report.")
	@ApiResponses({
			@ApiResponse(code = 200, message = "Success (for successful validation)", response = Output[].class),
			@ApiResponse(code = 500, message = "Error (If a problem occurred with processing the request)", response = String.class),
			@ApiResponse(code = 404, message = "Not found (for an invalid domain value)", response = String.class)
	})
    @RequestMapping(value = "/{domain}/api/validateMultiple", method = RequestMethod.POST, consumes = {MediaType.APPLICATION_JSON_VALUE, "application/ld+json"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Output[] validateMultiple(
			@ApiParam(required = true, name = "domain", value = "A fixed value corresponding to the specific validation domain.")
    		@PathVariable("domain") String domain,
			@ApiParam(required = true, name = "input", value = "The input for the validation (content and metadata for one or more RDF instances).")
			@RequestBody Input[] inputs
	) {
    	throw new ValidatorException(ValidatorException.message_support);
    }
    
    /**
     * Validates that the domain exists.
     * @param domain 
     */
    private DomainConfig validateDomain(String domain) {
		DomainConfig config = domainConfigs.getConfigForDomainName(domain);
        if (config == null || !config.isDefined() || !config.getChannels().contains(ValidatorChannel.REST_API)) {
            logger.error("The following domain does not exist: " + domain);
			throw new NotFoundException();
        }
        MDC.put("domain", domain);
        return config;
    }
    
    /**
     * Executes the validation
     * @param input Configuration of the current RDF
     * @param domainConfig 
     * @return Model SHACL report
     */
    private Model executeValidation(File inputFile, Input input, DomainConfig domainConfig) {
    	Model report = null;
    	
    	try {	
			SHACLValidator validator = ctx.getBean(SHACLValidator.class, inputFile, input.getValidationType(), input.getContentSyntax(), domainConfig);
			
			report = validator.validateAll();   
    	}catch(Exception e){
            logger.error("Error during the validation: " + e);
			throw new ValidatorException(ValidatorException.message_default);
    	}
		
		return report;
    }
    
    /**
     * Get report in the provided content type
     * @param shaclReport Model with the report
     * @param reportSyntax Content type
     * @return String
     */
    private String getShaclReport(Model shaclReport, String reportSyntax) {
		StringWriter writer = new StringWriter();		
		Lang lang = RDFLanguages.contentTypeToLang(reportSyntax);
		
		if(lang == null) {
			shaclReport.write(writer, null);
		}else {
			try {
				shaclReport.write(writer, lang.getName());
			}catch(Exception e) {
				shaclReport.write(writer, null);
			}
		}
		
		return writer.toString();
    }
    
    /**
     * Get the content from URL or BASE64
     * @param convert URL or BASE64 as String
     * @param convertSyntax Parameter convertSyntax
     * @return File
     */
    private File getContentFile(String convert, String convertSyntax) {
    	File outputFile = null;
    	
    	try {
    		outputFile = fileManager.getURLFile(convert);
    	}catch(Exception e) {
    		logger.info("Content is not a URL, treating as BASE64.");
    		outputFile = getBase64File(convert, convertSyntax);
    	}
    	
    	return outputFile;
    }
    
    /**
     * From Base64 string to File
     * @param base64Convert Base64 as String
     * @return File
     */
    private File getBase64File(String base64Convert, String contentSyntax) {
		if (contentSyntax == null) {
			logger.error(ValidatorException.message_parameters, "");
			throw new ValidatorException(ValidatorException.message_parameters);
		}

		Path tmpPath = fileManager.getTmpPath("");
    	try {
            // Construct the string from its BASE64 encoded bytes.
        	byte[] decodedBytes = Base64.getDecoder().decode(base64Convert);
			FileUtils.writeByteArrayToFile(tmpPath.toFile(), decodedBytes);
		} catch (IOException e) {
			logger.error("Error when transforming the Base64 into File.", e);
			throw new ValidatorException(ValidatorException.message_contentToValidate);
		}
    	
    	return tmpPath.toFile();
    }

    /**
     * Validates that the JSON send via parameters has all necessary data.
     * @param input JSON send via REST API
     * @return File to validate
     */
    private File getContentToValidate(Input input) {
    	String embeddingMethod = input.getEmbeddingMethod();
    	String contentToValidate = input.getContentToValidate();
    	List<RuleSet> externalRules = input.getExternalRules();
    	String contentSyntax = input.getContentSyntax();
    	
    	File contentFile = null;
    	
    	//EmbeddingMethod validation
    	if(embeddingMethod!=null) {
    		switch(embeddingMethod) {
    			case Input.embedding_URL:
    				try{
    					contentFile = fileManager.getURLFile(contentToValidate);
    				}catch(IOException e) {
						logger.error("Error when transforming the URL into File.", e);
						throw new ValidatorException(ValidatorException.message_contentToValidate);
					}
    				break;
    			case Input.embedding_BASE64:
    			    contentFile = getBase64File(contentToValidate, contentSyntax);
    			    break;
    			default:
    			    logger.error(ValidatorException.message_parameters, embeddingMethod);    			    
    				throw new ValidatorException(ValidatorException.message_parameters);
    		}
    	}else {
			contentFile = getContentFile(contentToValidate, contentSyntax);
    	}
    	
    	//ExternalRules validation
    	if(externalRules!=null) {
		    logger.error("Feature not supported.");
			throw new ValidatorException(ValidatorException.message_support);
    	}
    	
    	return contentFile;
    }

	@ApiOperation(hidden = true, value="")
	@RequestMapping(value = "/{domain}/api", method = RequestMethod.GET, produces = "application/ld+json")
	public ResponseEntity<String> hydraApi(@PathVariable String domain) throws IOException {
		DomainConfig domainConfig = validateDomain(domain);
		String content = FileUtils.readFileToString(new File(fileManager.getHydraDocsFolder(domainConfig.getDomainName()), "api.jsonld"), Charset.defaultCharset());
		HttpHeaders responseHeaders = new HttpHeaders();
		responseHeaders.setAccessControlExposeHeaders(Collections.singletonList("Link"));
		// Construct URL for vocabulary
		StringBuilder path = new StringBuilder();
		path.append(hydraServer);
		path.append(hydraRootPath);
		if (path.charAt(path.length()-1) != '/') {
			path.append('/');
		}
		path.append(domainConfig.getDomainName());
		responseHeaders.set("Link", "<"+path.toString()+"/api/vocab>; rel=\"http://www.w3.org/ns/hydra/core#apiDocumentation\"");
		return new ResponseEntity<>(content, responseHeaders, HttpStatus.OK);
	}

	@ApiOperation(hidden = true, value="")
	@RequestMapping(value = "/{domain}/api/contexts/{contextName}", method = RequestMethod.GET, produces = "application/ld+json")
	public String hydraContexts(@PathVariable String domain, @PathVariable String contextName) throws IOException {
		DomainConfig domainConfig = validateDomain(domain);
		return FileUtils.readFileToString(new File(fileManager.getHydraDocsFolder(domainConfig.getDomainName()), "EntryPoint.jsonld"), Charset.defaultCharset());
	}

	@ApiOperation(hidden = true, value="")
	@RequestMapping(value = "/{domain}/api/vocab", method = RequestMethod.GET, produces = "application/ld+json")
	public String hydraVocab(@PathVariable String domain) throws IOException {
		DomainConfig domainConfig = validateDomain(domain);
		return FileUtils.readFileToString(new File(fileManager.getHydraDocsFolder(domainConfig.getDomainName()), "vocab.jsonld"), Charset.defaultCharset());
	}

}
