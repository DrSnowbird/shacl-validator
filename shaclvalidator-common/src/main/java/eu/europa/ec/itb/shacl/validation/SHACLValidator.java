package eu.europa.ec.itb.shacl.validation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.topbraid.jenax.util.JenaUtil;
import org.topbraid.shacl.validation.ValidationUtil;

import eu.europa.ec.itb.shacl.ApplicationConfig;
import eu.europa.ec.itb.shacl.DomainConfig;
import eu.europa.ec.itb.shacl.util.Utils;

/**
 * 
 * Created by mfontsan on 26/02/2019
 *
 */
@Component
@Scope("prototype")
public class SHACLValidator implements ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(SHACLValidator.class);

    @Autowired
    private ApplicationConfig config;

    private InputStream inputToValidate;
    private File inputFileToValidate;
    private ApplicationContext ctx;
    private final DomainConfig domainConfig;
    private String validationType;
    private String contentSyntax;

    public SHACLValidator(File inputFileToValidate, String validationType, String contentSyntax, DomainConfig domainConfig) {
    	this.contentSyntax = contentSyntax;
    	this.inputFileToValidate = inputFileToValidate;
		try {
			this.inputToValidate = new FileInputStream(inputFileToValidate);
		} catch (FileNotFoundException e) {
			logger.error(e.getMessage());
		}
        this.validationType = validationType;
        this.domainConfig = domainConfig;
        if (validationType == null) {
            this.validationType = domainConfig.getType().get(0);
        }
    }
    
    public Model validateAll() {
    	logger.info("Starting validation..");
    	
        Model overallResult = null;
		try {
			overallResult = validateAgainstSHACL();
		} catch (FileNotFoundException e) {
			logger.error(e.getMessage());
		}
        
        return overallResult;
    }
    
    /**
     * Validation of the model
     * @return Model The Jena model with the report
     * @throws FileNotFoundException
     */
    public Model validateAgainstSHACL() throws FileNotFoundException {
        File shaclFile = getSHACLFile();
        List<Model> shaclReports = new ArrayList<Model>();
        List<File> shaclFiles = new ArrayList<>();
        if (shaclFile != null && shaclFile.exists()) {
            if (shaclFile.isFile()) {
                // We are pointing to a single master SHACL file.
            	shaclFiles.add(shaclFile);
            } else {
                // All SHACL are processed.
                File[] files = shaclFile.listFiles();
                if (files != null) {
                    for (File aSHACLFile: files) {
                        if (aSHACLFile.isFile() && config.getAcceptedSHACLExtensions().contains(FilenameUtils.getExtension(aSHACLFile.getName().toLowerCase()))) {
                        	shaclFiles.add(aSHACLFile);
                        }
                    }
                }
            }
        }
        if (shaclFiles.isEmpty()) {
            logger.info("No SHACL to validate against ["+shaclFile+"]");
            throw new FileNotFoundException();
        } else {
            for (File aSHACLFile: shaclFiles) {
                logger.info("Validating against ["+aSHACLFile.getName()+"]");
                Model shaclReport = validateSHACL(Utils.getInputStreamForValidation(inputToValidate), aSHACLFile);                
                shaclReports.add(shaclReport);
                logger.info("Validated against ["+aSHACLFile.getName()+"]");
            }
            Model report = SHACLValidationReport.mergeShaclReport(shaclReports);
            
            return report;
        }
    }
    
    /**
     * Validate the RDF against one shape file
     * @param inputSource File to validate as InputStream
     * @param shaclFile SHACL file
     * @return Model The Jena Model with the report
     */
    private Model validateSHACL(InputStream inputSource, File shaclFile){
    	Model reportModel = null;
    	
    	try {
			// Get data to validate from file
	        Model shaclModel = getDataModel(shaclFile, null);
	        Model dataModel = getDataModel(inputFileToValidate, shaclModel);
	        
			// Perform the validation of data, using the shapes model. Do not validate any shapes inside the data model.
			Resource resource = ValidationUtil.validateModel(dataModel, shaclModel, false);		
			reportModel = resource.getModel();
			reportModel.setNsPrefix("sh", "http://www.w3.org/ns/shacl#");
			
    	}catch(Exception e){
    		logger.error("Error during the SHACL validation. " + e.getMessage());
            throw new IllegalStateException(e);
    	}
    	
    	return reportModel;
    }
    
    private File getSHACLFile() {  	
        return Paths.get(config.getResourceRoot(), domainConfig.getDomain(), domainConfig.getShaclFile().get(validationType)).toFile();
    }
    
    /**
     * 
     * @param dataFile File with RDF data
     * @param shapesModel The Jena model containing the shacl defintion (needed to set the proper prefixes on the input data)
     * @return Model Jena Model containing the data from dataFile
     * @throws FileNotFoundException
     */
    private Model getDataModel(File dataFile, Model shapesModel) throws FileNotFoundException {    	
    	String extension = FilenameUtils.getExtension(dataFile.getName());
    	Lang lang = null;
		InputStream dataStream = new FileInputStream(dataFile);
            	
		// Upload the data in the Model. First set the prefixes of the model to those of the shapes model to avoid mismatches.
		Model dataModel = JenaUtil.createMemoryModel();
		
		if(shapesModel!=null) {
			dataModel.setNsPrefixes(shapesModel.getNsPrefixMap());
		}
		
		if(shapesModel!=null && this.contentSyntax!=null) {
			lang = RDFLanguages.contentTypeToLang(this.contentSyntax);
			if(lang==null) RDFLanguages.fileExtToLang(extension);
		}else {
			lang = RDFLanguages.fileExtToLang(extension);
		}
		if(lang==null) {
			try {
				dataStream.close();
			} catch (IOException e) {
				logger.error("Error when closing InputStream: ", e);
				throw new IllegalStateException("Error when closing InputStream.");
			}
			logger.error("RDF Language does not exist.");
			throw new IllegalStateException("RDF Language does not exist.");
		}
		dataModel.read(dataStream, null, lang.getName());

		try {
			dataStream.close();
		} catch (IOException e) {
			logger.error("Error when closing InputStream: ", e);
			throw new IllegalStateException("Error when closing InputStream.");
		}
		
		return dataModel;
	}

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        this.ctx = ctx;
    }
}
