package sds.chemicalfileparser.commandhandlers;

import sds.chemicalfileparser.domain.commands.ParseFile;
import sds.chemicalfileparser.domain.events.RecordParseFailed;
import sds.chemicalfileparser.domain.events.RecordParsed;
import sds.chemicalfileparser.domain.events.FileParsed;
import sds.chemicalfileparser.domain.events.FileParseFailed;
import com.epam.indigo.Indigo;
import com.epam.indigo.IndigoObject;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import sds.chemicalfileparser.domain.models.Field;
import com.npspot.jtransitlight.JTransitLightException;
import com.npspot.jtransitlight.consumer.ReceiverBusControl;
import com.npspot.jtransitlight.publisher.IBusControl;
import com.sds.storage.BlobInfo;
import com.sds.storage.BlobStorage;
import com.sds.storage.Guid;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import sds.messaging.callback.AbstractMessageProcessor;

@Component
public class ParseFileProcessor extends AbstractMessageProcessor<ParseFile> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseFileProcessor.class);

    ReceiverBusControl receiver;
    IBusControl bus;
    BlobStorage storage;

    @Autowired
    public ParseFileProcessor(
            ReceiverBusControl receiver, 
            IBusControl bus
            ,
            BlobStorage storage
    ) throws JTransitLightException, IOException {
        this.bus = bus;
        this.receiver = receiver;
        this.storage = storage;
    }


    public void process(ParseFile message) {
        LOGGER.debug("Parser trigged.");
        long failedRecords = 0;
        long parsedRecords = 0;
        IndigoObject records = null;
        try {
            Date begin = new Date();
            File directory = new File(System.getenv("OSDR_TEMP_FILES_FOLDER"));
            File tempFile = File.createTempFile("temp", ".tmp", directory);

            Indigo indigo = new Indigo();
            indigo.setOption("ignore-stereochemistry-errors", "true");
            indigo.setOption("unique-dearomatization", "false");
            indigo.setOption("ignore-noncritical-query-features", "true");
            indigo.setOption("timeout", "600000");
            
            BlobInfo blob = storage.getFileInfo(new Guid(message.getBlobId()), message.getBucket());

            if (blob == null) {
                throw new FileNotFoundException(String.format("Blob with Id %s not found in bucket %s", new Guid(message.getBlobId()), message.getBucket()));
            }

            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                IOUtils.copy(storage.getFileStream(new Guid(message.getBlobId()), message.getBucket()), out);
            }

            switch (FilenameUtils.getExtension(blob.getFileName()).toLowerCase()) {
                case "mol":
                case "sdf":
                    records = indigo.iterateSDFile(tempFile.getCanonicalPath());
                    LOGGER.debug("Get records iterator as MOL/SDF.");
                    break;
                case "cdx":
                    records = indigo.iterateCDXFile(tempFile.getCanonicalPath());
                    LOGGER.debug("Get records enumerator as CDX.");
                    break;
                default:
                    FileParseFailed fileParseFailed = new FileParseFailed();
                    fileParseFailed.setId(message.getId());
                    fileParseFailed.setParsedRecords(parsedRecords);
                    fileParseFailed.setFailedRecords(failedRecords);
                    fileParseFailed.setTotalRecords(parsedRecords + failedRecords);
                    fileParseFailed.setMessage(String.format("Cannot parse chemical file %s. Format is not supported.", blob.getFileName()));
                    fileParseFailed.setCorrelationId(message.getCorrelationId());
                    fileParseFailed.setUserId(message.getUserId());
                    fileParseFailed.setTimeStamp(getTimestamp());

                    bus.publish(fileParseFailed);

                    return;
            }

            String bucket = message.getBucket();

            long index = 0;

            List<String> uniqueFieldNames = new ArrayList<String>();

            Iterator<IndigoObject> recordsIterator = records.iterator();
            
            while (recordsIterator.hasNext()) {

                try {
                    IndigoObject record = recordsIterator.next();

                    String mol = record.molfile();

                    Guid blobId = Guid.newGuid();

                    storage.addFile(blobId, blobId.toString() + ".mol", mol.getBytes(), "chemical/x-mdl-molfile", bucket, null);
                    //Thread.sleep(10000);
                    List<Field> fields = new ArrayList<>();

                    Iterator<IndigoObject> propertiesIterator = record.iterateProperties().iterator();
                    while (propertiesIterator.hasNext()) {
                        IndigoObject property = propertiesIterator.next(); 
                        fields.add(new Field(property.name(), property.rawData()));
                        
                        if(!uniqueFieldNames.contains(property.name())) {
                            uniqueFieldNames.add(property.name());
                        }
                    }
                    //Thread.sleep(10000);
                    RecordParsed recordParsed = new RecordParsed();
                    recordParsed.setId(UUID.randomUUID());
                    recordParsed.setFileId(message.getId());
                    recordParsed.setIndex(index);
                    recordParsed.setFields(fields);
                    recordParsed.setBucket(message.getBucket());
                    recordParsed.setBlobId(blobId);
                    recordParsed.setCorrelationId(message.getCorrelationId());
                    recordParsed.setUserId(message.getUserId());
                    recordParsed.setTimeStamp(getTimestamp());
                    bus.publish(recordParsed);

                    parsedRecords++;
                    fields = null;
                    
                } catch (Exception ex) {
                    RecordParseFailed recordParseFailed = new RecordParseFailed();
                    recordParseFailed.setId(UUID.randomUUID());
                    recordParseFailed.setFileId(message.getId());
                    recordParseFailed.setIndex(index);
                    recordParseFailed.setCorrelationId(message.getCorrelationId());
                    recordParseFailed.setMessage(ex.getMessage() == null ? " " : ex.getMessage());
                    recordParseFailed.setUserId(message.getUserId());
                    recordParseFailed.setTimeStamp(getTimestamp());

                    bus.publish(recordParseFailed);

                    failedRecords++;
                }
                index++;
            }
            FileParsed fileParsed = new FileParsed();
            fileParsed.setId(message.getId());
            fileParsed.setParsedRecords(parsedRecords);
            fileParsed.setFailedRecords(failedRecords);
            fileParsed.setTotalRecords(parsedRecords + failedRecords);
            fileParsed.setFields(uniqueFieldNames);
            fileParsed.setCorrelationId(message.getCorrelationId());
            fileParsed.setUserId(message.getUserId());
            fileParsed.setTimeStamp(getTimestamp());
            
            bus.publish(fileParsed);
            records.dispose();
            Files.delete(tempFile.toPath());

            LOGGER.debug("Temp file has been deleted.");
            LOGGER.debug("Time to parse: " + (new Date().getTime() - begin.getTime()) + ", total records: " + index + ", failed records: " + failedRecords + ".");
        } catch (Throwable ex) {
            ex.printStackTrace();
            FileParseFailed fileParseFailed = new FileParseFailed();
            fileParseFailed.setId(message.getId());
            fileParseFailed.setParsedRecords(parsedRecords);
            fileParseFailed.setFailedRecords(failedRecords);
            fileParseFailed.setTotalRecords(parsedRecords + failedRecords);
            fileParseFailed.setMessage(ex.getMessage());
            fileParseFailed.setCorrelationId(message.getCorrelationId());
            fileParseFailed.setUserId(message.getUserId());
            fileParseFailed.setTimeStamp(getTimestamp());

            bus.publish(fileParseFailed);
        }
        
    }

    private String getTimestamp() {
        //("yyyy-MM-dd'T'HH:mm:ss'Z'")
        return LocalDateTime.now().toString();
    }

}
