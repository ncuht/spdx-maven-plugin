/*
 * Copyright 2014 Source Auditor Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License" );
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.spdx.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spdx.rdfparser.InvalidSPDXAnalysisException;
import org.spdx.rdfparser.SpdxDocumentContainer;
import org.spdx.rdfparser.SpdxPackageVerificationCode;
import org.spdx.rdfparser.license.AnyLicenseInfo;
import org.spdx.rdfparser.license.ConjunctiveLicenseSet;
import org.spdx.rdfparser.model.DoapProject;
import org.spdx.rdfparser.model.Relationship;
import org.spdx.rdfparser.model.Relationship.RelationshipType;
import org.spdx.rdfparser.model.SpdxFile;
import org.spdx.rdfparser.model.SpdxFile.FileType;
import org.spdx.rdfparser.model.SpdxPackage;
import org.spdx.rdfparser.model.SpdxSnippet;
import org.spdx.spdxspreadsheet.InvalidLicenseStringException;


/**
 * Collects SPDX file information from directories.
 * <p>
 * The method <code>collectFilesInDirectory(FileSet[] filesets)</code> will scan and create SPDX File information for
 * all files in the filesets.
 *
 * @author Gary O'Neall
 */
public class SpdxFileCollector
{
    static Logger logger = LoggerFactory.getLogger( SpdxFileCollector.class );
    // constants for mapping extensions to types.
    static Set<String> SOURCE_EXTENSIONS = new HashSet<>();
    static Set<String> BINARY_EXTENSIONS = new HashSet<>();
    static Set<String> ARCHIVE_EXTENSIONS = new HashSet<>();
    static final String SPDX_FILE_TYPE_CONSTANTS_PROP_PATH = "resources/SpdxFileTypeConstants.prop";
    static final String SPDX_PROP_FILETYPE_SOURCE = "SpdxSourceExtensions";
    static final String SPDX_PROP_FILETYPE_BINARY = "SpdxBinaryExtensions";
    static final String SPDX_PROP_FILETYPE_ARCHIVE = "SpdxArchiveExtensions";

    static
    {
        loadFileExtensionConstants();
    }

    static final String SHA1_ALGORITHM = "SHA-1";
    private static MessageDigest digest;

    static
    {
        try
        {
            digest = MessageDigest.getInstance( SHA1_ALGORITHM );
        }
        catch ( NoSuchAlgorithmException e )
        {
            logger.error( "No such algorithm error initializing the SPDX file collector - SHA1", e );
            digest = null;
        }
    }

    static final List<String> checksumAlgorithms = Arrays.asList( "SHA-224", "SHA-256", "SHA-384",
            "SHA-512", "MD2", "MD4", "MD5", "MD6" );


    Set<AnyLicenseInfo> licensesFromFiles = new HashSet<>();
    /**
     * Map of fileName, SPDXFile for all files in the SPDX document
     */
    Map<String, SpdxFile> spdxFiles = new HashMap<>();
    List<SpdxSnippet> spdxSnippets = new ArrayList<>();

    FileSetManager fileSetManager = new FileSetManager();
    private Log log;

    /**
     * SpdxFileCollector collects SPDX file information for files
     */
    public SpdxFileCollector( Log log )
    {
        this.log = log;
    }

    /**
     * Load file type constants from the properties file
     */
    private static void loadFileExtensionConstants()
    {
        Properties prop = new Properties();
        try ( InputStream is = SpdxFileCollector.class.getClassLoader().getResourceAsStream(
                SPDX_FILE_TYPE_CONSTANTS_PROP_PATH ) )
        {
            if ( is == null )
            {
                logger.error( "Unable to load properties file " + SPDX_FILE_TYPE_CONSTANTS_PROP_PATH );
            }
            prop.load( is );
            String sourceExtensionStr = prop.getProperty( SPDX_PROP_FILETYPE_SOURCE );
            loadSetUpcase( SOURCE_EXTENSIONS, sourceExtensionStr );
            String binaryExtensionStr = prop.getProperty( SPDX_PROP_FILETYPE_BINARY );
            loadSetUpcase( BINARY_EXTENSIONS, binaryExtensionStr );
            String archiveExtensionStr = prop.getProperty( SPDX_PROP_FILETYPE_ARCHIVE );
            loadSetUpcase( ARCHIVE_EXTENSIONS, archiveExtensionStr );
        }
        catch ( IOException e )
        {
            logger.warn(
                    "WARNING: Error reading SpdxFileTypeConstants properties file.  All file types will be mapped to Other." );
        }
    }

    /**
     * Load a set from a comma delimited string of values trimming and upcasing all values
     *
     * @param set
     * @param str
     */
    private static void loadSetUpcase( Set<String> set, String str )
    {
        String[] values = str.split( "," );
        for ( String value : values )
        {
            set.add( value.toUpperCase().trim() );
        }
    }

    /**
     * Collect file information in the directory (including subdirectories).
     *
     * @param fileSets                FileSets containing the description of the directory to be scanned
     * @param baseDir                 project base directory used to construct the relative paths for the SPDX files
     * @param pathPrefix              Path string which should be removed when creating the SPDX file name
     * @param defaultFileInformation  Information on default SPDX field data for the files
     * @param pathSpecificInformation Map of path to file information used to override the default file information
     * @param relationshipType        Type of relationship to the project package
     * @param projectPackage          Package to which the files belong
     * @param container               contains the extracted license infos that may be needed for license parsing
     * @throws SpdxCollectionException
     */
    public void collectFiles( FileSet[] fileSets, String baseDir, SpdxDefaultFileInformation defaultFileInformation, Map<String, SpdxDefaultFileInformation> pathSpecificInformation, SpdxPackage projectPackage, RelationshipType relationshipType, SpdxDocumentContainer container ) throws SpdxCollectionException
    {
        for ( FileSet fileSet : fileSets )
        {
            String[] includedFiles = fileSetManager.getIncludedFiles( fileSet );
            for ( String includedFile : includedFiles )
            {
                String filePath = fileSet.getDirectory() + File.separator + includedFile;
                File file = new File( filePath );
                String relativeFilePath = file.getAbsolutePath().substring( baseDir.length() + 1 ).replace( '\\', '/' );
                SpdxDefaultFileInformation fileInfo = findDefaultFileInformation( relativeFilePath,
                        pathSpecificInformation );
                if ( fileInfo == null )
                {
                    fileInfo = defaultFileInformation;
                }

                String outputFileName;
                if ( fileSet.getOutputDirectory() != null )
                {
                    outputFileName = fileSet.getOutputDirectory() + File.separator + includedFile;
                }
                else
                {
                    outputFileName = file.getAbsolutePath().substring( baseDir.length() + 1 );
                }
                collectFile( file, outputFileName, fileInfo, relationshipType, projectPackage, container );
            }
        }
    }

    /**
     * Find the most appropriate file information based on the lowset level match (closedt to file)
     *
     * @param filePath
     * @param pathSpecificInformation
     * @return
     */
    private SpdxDefaultFileInformation findDefaultFileInformation( String filePath, Map<String, SpdxDefaultFileInformation> pathSpecificInformation )
    {
        if ( log != null )
        {
            log.debug( "Checking for file path " + filePath );
        }
        SpdxDefaultFileInformation retval = pathSpecificInformation.get( filePath );
        if ( retval != null )
        {
            if ( log != null )
            {
                log.debug( "Found filepath" );
            }
            return retval;
        }
        // see if any of the parent directories contain default information which should be used
        String parentPath = filePath;
        int parentPathIndex = 0;
        do
        {
            parentPathIndex = parentPath.lastIndexOf( "/" );
            if ( parentPathIndex > 0 )
            {
                parentPath = parentPath.substring( 0, parentPathIndex );
                retval = pathSpecificInformation.get( parentPath );
            }
        } while ( retval == null && parentPathIndex > 0 );
        if ( retval != null )
        {
            debug( "Found directory containing file path for path specific information.  File path: " + parentPath );
        }
        return retval;
    }

    private void debug( String msg )
    {
        if ( this.getLog() != null )
        {
            this.getLog().debug( msg );
        }
        else
        {
            logger.debug( msg );
        }
    }

    /**
     * Collect SPDX information for a specific file
     *
     * @param file
     * @param outputFileName   Path to the output file name relative to the root of the output archive file
     * @param relationshipType Type of relationship to the project package
     * @param projectPackage   Package to which the files belong
     * @throws SpdxCollectionException
     */
    private void collectFile( File file, String outputFileName, SpdxDefaultFileInformation fileInfo, RelationshipType relationshipType, SpdxPackage projectPackage, SpdxDocumentContainer container ) throws SpdxCollectionException
    {
        if ( spdxFiles.containsKey( file.getPath() ) )
        {
            return; // already added from a previous scan
        }
        SpdxFile spdxFile = convertToSpdxFile( file, outputFileName, fileInfo );
        Relationship relationship = new Relationship( projectPackage, relationshipType, "" );
        try
        {
            spdxFile.addRelationship( relationship );
        }
        catch ( InvalidSPDXAnalysisException e )
        {
            if ( log != null )
            {
                log.error( "Spdx exception creating file relationship: " + e.getMessage(), e );
            }
            throw new SpdxCollectionException( "Error creating SPDX file relationship: " + e.getMessage() );
        }
        if ( fileInfo.getSnippets() != null )
        {
            for ( SnippetInfo snippet : fileInfo.getSnippets() )
            {
                SpdxSnippet spdxSnippet;
                try
                {
                    spdxSnippet = convertToSpdxSnippet( snippet, spdxFile, container );
                }
                catch ( InvalidLicenseStringException e )
                {
                    logger.error( "Invalid license string creating snippet", e );
                    throw new SpdxCollectionException(
                            "Error processing SPDX snippet information.  Invalid license string specified in snippet.",
                            e );
                }
                catch ( SpdxBuilderException e )
                {
                    logger.error( "Error creating SPDX snippet", e );
                    throw new SpdxCollectionException( "Error creating SPDX snippet information.", e );
                }
                spdxSnippets.add( spdxSnippet );
            }
        }
        spdxFiles.put( file.getPath(), spdxFile );
        AnyLicenseInfo[] licenseInfoFromFiles = spdxFile.getLicenseInfoFromFiles();
        licensesFromFiles.addAll( Arrays.asList( licenseInfoFromFiles ) );
    }

    private SpdxSnippet convertToSpdxSnippet( SnippetInfo snippet, SpdxFile spdxFile, SpdxDocumentContainer container ) throws InvalidLicenseStringException, SpdxBuilderException
    {
        //TODO: Add annotations to snippet
        SpdxSnippet retval = new SpdxSnippet( snippet.getName(), snippet.getComment(),
                new org.spdx.rdfparser.model.Annotation[0], new Relationship[0],
                snippet.getLicenseConcluded( container ), snippet.getLicenseInfoInSnippet( container ),
                snippet.getCopyrightText(), snippet.getLicensComment(), spdxFile, snippet.getByteRange( spdxFile ),
                snippet.getLineRange( spdxFile ) );
        return retval;
    }

    /**
     * @param file
     * @param outputFileName         Path to the output file name relative to the root of the output archive file
     * @param defaultFileInformation Information on default SPDX field data for the files
     * @return
     * @throws SpdxCollectionException
     */
    private SpdxFile convertToSpdxFile( File file, String outputFileName, SpdxDefaultFileInformation defaultFileInformation ) throws SpdxCollectionException
    {
        String relativePath = convertFilePathToSpdxFileName( outputFileName );
        FileType[] fileTypes = new FileType[] {extensionToFileType( getExtension( file ) )};
        String sha1 = generateSha1( file );
        AnyLicenseInfo concludedLicense = null;
        AnyLicenseInfo license = null;
        String licenseComment = defaultFileInformation.getLicenseComment();
        if ( isSourceFile( fileTypes ) && file.length() < SpdxSourceFileParser.MAXIMUM_SOURCE_FILE_LENGTH )
        {
            List<AnyLicenseInfo> fileSpdxLicenses = null;
            try
            {
                fileSpdxLicenses = SpdxSourceFileParser.parseFileForSpdxLicenses( file );
            }
            catch ( SpdxSourceParserException ex )
            {
                if ( log != null )
                {
                    log.error( "Error parsing for SPDX license ID's", ex );
                }
            }
            if ( fileSpdxLicenses != null && fileSpdxLicenses.size() > 0 )
            {
                // The file has declared licenses of the form SPDX-License-Identifier: licenseId
                if ( fileSpdxLicenses.size() == 1 )
                {
                    license = fileSpdxLicenses.get( 0 );
                }
                else
                {
                    license = new ConjunctiveLicenseSet( fileSpdxLicenses.toArray( new AnyLicenseInfo[0] ) );
                }
                if ( licenseComment == null )
                {
                    licenseComment = "";
                }
                else if ( licenseComment.length() > 0 )
                {
                    licenseComment = licenseComment.concat( ";  " );
                }
                licenseComment = licenseComment.concat( "This file contains SPDX-License-Identifiers for " );
                licenseComment = licenseComment.concat( license.toString() );
            }
        }
        if ( license == null )
        {
            license = defaultFileInformation.getDeclaredLicense();
            concludedLicense = defaultFileInformation.getConcludedLicense();
        }
        else
        {
            concludedLicense = license;
        }

        String copyright = defaultFileInformation.getCopyright();
        String notice = defaultFileInformation.getNotice();
        String comment = defaultFileInformation.getComment();
        String[] contributors = defaultFileInformation.getContributors();
        DoapProject[] artifactOf = new DoapProject[0];

        SpdxFile retval = null;
        //TODO: Add annotation
        //TODO: Add optional checksums
        try
        {
            retval = new SpdxFile( relativePath, fileTypes, sha1, concludedLicense, new AnyLicenseInfo[] {license},
                    licenseComment, copyright, artifactOf, comment );
            retval.setFileContributors( contributors );
            retval.setNoticeText( notice );
        }
        catch ( InvalidSPDXAnalysisException e )
        {
            if ( log != null )
            {
                log.error( "Spdx exception creating file: " + e.getMessage(), e );
            }
            throw new SpdxCollectionException( "Error creating SPDX file: " + e.getMessage() );
        }

        return retval;
    }

    /**
     * @param fileTypes
     * @return true if the fileTypes contain a source file type
     */
    protected boolean isSourceFile( FileType[] fileTypes )
    {
        for ( FileType ft : fileTypes )
        {
            if ( ft == FileType.fileType_source )
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Create the SPDX file name from a system specific path name
     *
     * @param filePath system specific file path relative to the top of the archive root to the top of the archive
     *                 directory where the file is stored.
     * @return
     */
    public String convertFilePathToSpdxFileName( String filePath )
    {
        String result = filePath.replace( '\\', '/' );
        if ( !result.startsWith( "./" ) )
        {
            result = "./" + result;
        }
        return result;
    }

    public String getExtension( File file )
    {
        String fileName = file.getName();
        int lastDot = fileName.lastIndexOf( '.' );
        if ( lastDot < 1 )
        {
            return "";
        }
        else
        {
            return fileName.substring( lastDot + 1 );
        }
    }

    private static FileType extensionToFileType( String fileExtension )
    {
        //TODO: Add other file types
        if ( fileExtension == null )
        {
            return FileType.fileType_other;
        }
        String upperExtension = fileExtension.toUpperCase();
        if ( SOURCE_EXTENSIONS.contains( upperExtension ) )
        {
            return FileType.fileType_source;
        }
        else if ( BINARY_EXTENSIONS.contains( upperExtension ) )
        {
            return FileType.fileType_binary;
        }
        else if ( ARCHIVE_EXTENSIONS.contains( upperExtension ) )
        {
            return FileType.fileType_archive;
        }
        else
        {
            return FileType.fileType_other;
        }
        //TODO: Add new file types for SPDX 2.0
    }

    /**
     * @return SPDX Files which have been acquired through the collectFilesInDirectory method
     */
    public SpdxFile[] getFiles()
    {
        return spdxFiles.values().toArray( new SpdxFile[0] );
    }

    /**
     * @return SPDX Snippets collected through the collectFilesInDirectory method
     */
    public List<SpdxSnippet> getSnippets()
    {
        return this.spdxSnippets;
    }

    /**
     * @return all license information used in the SPDX files
     */
    public AnyLicenseInfo[] getLicenseInfoFromFiles()
    {
        return licensesFromFiles.toArray( new AnyLicenseInfo[0] );
    }

    /**
     * Create a verification code from all SPDX files collected
     *
     * @param spdxFilePath Complete file path for the SPDX file - this will be excluded from the verification code
     * @return
     * @throws NoSuchAlgorithmException
     */
    public SpdxPackageVerificationCode getVerificationCode( String spdxFilePath ) throws NoSuchAlgorithmException
    {
        List<String> excludedFileNamesFromVerificationCode = new ArrayList<>();

        if ( spdxFilePath != null && spdxFiles.containsKey( spdxFilePath ) )
        {
            excludedFileNamesFromVerificationCode.add( spdxFiles.get( spdxFilePath ).getName() );
        }
        SpdxPackageVerificationCode verificationCode;
        verificationCode = calculatePackageVerificationCode( spdxFiles.values(),
                excludedFileNamesFromVerificationCode );
        return verificationCode;
    }

    /**
     * Calculate the package verification code for a collection of SPDX files
     *
     * @param spdxFiles                             Files used to calculate the verification code
     * @param excludedFileNamesFromVerificationCode List of file names to exclude
     * @return
     * @throws NoSuchAlgorithmException
     */
    private SpdxPackageVerificationCode calculatePackageVerificationCode( Collection<SpdxFile> spdxFiles, List<String> excludedFileNamesFromVerificationCode ) throws NoSuchAlgorithmException
    {
        List<String> fileChecksums = new ArrayList<>();
        for ( SpdxFile file : spdxFiles )
        {
            if ( includeInVerificationCode( file.getName(), excludedFileNamesFromVerificationCode ) )
            {
                fileChecksums.add( file.getSha1() );
            }
        }
        Collections.sort( fileChecksums );
        MessageDigest verificationCodeDigest = MessageDigest.getInstance( SHA1_ALGORITHM );
        for ( String fileChecksum : fileChecksums )
        {
            byte[] hashInput = fileChecksum.getBytes( StandardCharsets.UTF_8 );
            verificationCodeDigest.update( hashInput );
        }
        String value = convertChecksumToString( verificationCodeDigest.digest() );
        return new SpdxPackageVerificationCode( value, excludedFileNamesFromVerificationCode.toArray( new String[0] ) );
    }

    private boolean includeInVerificationCode( String name, List<String> excludedFileNamesFromVerificationCode )
    {
        for ( String s : excludedFileNamesFromVerificationCode )
        {
            if ( s.equals( name ) )
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts an array of bytes to a string compliant with the SPDX sha1 representation
     *
     * @param digestBytes
     * @return
     */
    public static String convertChecksumToString( byte[] digestBytes )
    {
        StringBuilder sb = new StringBuilder();
        for ( byte digestByte : digestBytes )
        {
            String hex = Integer.toHexString( 0xff & digestByte );
            if ( hex.length() < 2 )
            {
                sb.append( '0' );
            }
            sb.append( hex );
        }
        return sb.toString();
    }

    private static String generateChecksumWithDigest(File file, MessageDigest digest) throws SpdxCollectionException
    {
        digest.reset();
        byte[] buffer = new byte[2048];
        try ( InputStream in = new FileInputStream( file ) )
        {
            int numBytes = in.read( buffer );
            while ( numBytes >= 0 )
            {
                digest.update( buffer, 0, numBytes );
                numBytes = in.read( buffer );
            }
        }
        catch ( IOException e )
        {
            String error = "IO error while calculating the " + digest.getAlgorithm() + " checksum";
            logger.warn( error );
            throw new SpdxCollectionException( error );
        }
        return convertChecksumToString( digest.digest() );
    }

    /**
     * Generate the Sha1 for a given file.  Must have read access to the file.
     *
     * @param file file to generate checksum for
     * @return SHA1 checksum of the input file
     * @throws SpdxCollectionException
     */
    public static String generateSha1( File file ) throws SpdxCollectionException
    {
        if ( digest == null )
        {
            try
            {
                digest = MessageDigest.getInstance( SHA1_ALGORITHM );
            }
            catch ( NoSuchAlgorithmException e )
            {
                throw ( new SpdxCollectionException(
                        "Unable to create the message digest for generating the File SHA1" ) );
            }
        }
        digest.reset();
        return generateChecksumWithDigest( file, digest );
    }

    /**
     *
     * @param file file whose checksum is to be generated
     * @param algorithm algorithm to generate the checksum. Allowed values are SHA-1, SHA-224, SHA-256, SHA-384, SHA-512, MD2, MD4, MD5, MD6
     * @return checksum of the file
     * @throws SpdxCollectionException if the input algorithm is invalid or unavailable
     */
    public static String generateOptionalChecksum(File file, String algorithm) throws SpdxCollectionException
    {
        if ( !checksumAlgorithms.contains( algorithm ) )
        {
            throw new SpdxCollectionException(algorithm + " algorithm is not supported for creating file checksums.");
        }

        try
        {
            MessageDigest digest = MessageDigest.getInstance( algorithm );
            return generateChecksumWithDigest( file, digest );
        }
        catch ( NoSuchAlgorithmException e )
        {
            throw new SpdxCollectionException(e);
        }
    }

    public void setLog( Log log )
    {
        this.log = log;
    }

    private Log getLog()
    {
        return this.log;
    }
}
