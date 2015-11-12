/*
 * The Gemma project
 * 
 * Copyright (c) 2007 University of British Columbia
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package chibi.gemmaanalysis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.lang3.time.StopWatch;

import ubic.gemma.expression.experiment.service.ExpressionExperimentService;
import ubic.gemma.genome.gene.service.GeneService;
import ubic.gemma.genome.taxon.service.TaxonService;
import ubic.gemma.model.genome.Gene;
import ubic.gemma.model.genome.Taxon;
import ubic.gemma.ontology.providers.GeneOntologyService;
import ubic.gemma.util.AbstractSpringAwareCLI;
import cern.colt.list.ObjectArrayList;

/**
 * Run frequent itemset analysis. WARNING probably broken.
 * 
 * @author xwan
 * @version $Id$
 */
public class MetaLinkFinderCli extends AbstractSpringAwareCLI {

    /*
     * (non-Javadoc)
     * 
     * @see ubic.gemma.util.AbstractCLI#buildOptions()
     */
    private GeneService geneService = null;
    private GeneOntologyService goService;
    private ExpressionExperimentService eeService = null;
    private boolean writeClusteringTree = false;
    private boolean writeLinkMatrix = false;
    private String matrixFile = null, eeMapFile = null, treeFile = null, taxonName = null;
    private Taxon taxon = null;
    private LinkMatrix linkMatrix = null;

    @SuppressWarnings("static-access")
    @Override
    protected void buildOptions() {
        Option writeLinkMatrixo = OptionBuilder
                .withDescription(
                        "Giving this option will generate the new link matrix, Otherwise reading the link matrix from file" )
                .withLongOpt( "linkMatrix" ).create( 'l' );
        addOption( writeLinkMatrixo );
        Option writeTree = OptionBuilder
                .withDescription(
                        "Giving this option will generate the new clustering tree, Otherwise reading the tree from file" )
                .withLongOpt( "clusteringTree" ).create( 'c' );
        addOption( writeTree );
        Option matrixFileo = OptionBuilder.hasArg().withArgName( "Bit Matrixfile" ).isRequired()
                .withDescription( "The file for saving bit matrix" ).withLongOpt( "matrixfile" ).create( 'm' );
        addOption( matrixFileo );

        Option mapFile = OptionBuilder.hasArg().withArgName( "Expression Experiment Map File" ).isRequired()
                .withDescription( "The File for Saving the Expression Experiment Mapping" ).withLongOpt( "mapfile" )
                .create( 'e' );
        addOption( mapFile );

        Option treeFileo = OptionBuilder.hasArg().withArgName( "Clustering Tree File" ).isRequired()
                .withDescription( "The file for saving clustering tree" ).withLongOpt( "treefile" ).create( 't' );
        addOption( treeFileo );

        Option specieso = OptionBuilder.hasArg().withArgName( "The name of the species" ).isRequired()
                .withDescription( "The name of the species" ).withLongOpt( "species" ).create( 's' );
        addOption( specieso );

    }

    /**
     * 
     */
    @Override
    protected void processOptions() {
        super.processOptions();
        if ( hasOption( 'l' ) ) {
            this.writeLinkMatrix = true;
        }

        if ( hasOption( 'c' ) ) {
            this.writeClusteringTree = true;
        }

        if ( hasOption( 'm' ) ) {
            this.matrixFile = getOptionValue( 'm' );
        }

        if ( hasOption( 'e' ) ) {
            this.eeMapFile = getOptionValue( 'e' );
        }

        if ( hasOption( 't' ) ) {
            this.treeFile = getOptionValue( 't' );
        }
        if ( hasOption( 's' ) ) {
            this.taxonName = getOptionValue( 's' );
        }
    }

    /**
     * 
     */
    void interactiveQuery() {
        BufferedReader bfr = new BufferedReader( new InputStreamReader( System.in ) );
        String geneName;
        int count = 0;
        try {
            // Hit CTRL-Z on PC's to send EOF, CTRL-D on Unix
            while ( true ) {
                // Read a character from keyboard
                System.out.println( "The Gene ID: (Press CTRL-Z or CTRL-D to Stop)" );
                System.out.print( ">" );
                geneName = bfr.readLine();
                if ( geneName == null ) break;
                System.out.print( "The Stringency:" );
                String tmp = bfr.readLine();
                if ( tmp == null ) break;
                count = Integer.valueOf( tmp.trim() ).intValue();
                // Gene gene = geneService.load(Long.valueOf(geneName).longValue());
                Gene gene = geneService.findByOfficialSymbol( geneName, taxon );
                if ( gene != null ) {
                    System.out.println( "Got " + geneName + " " + count );
                    linkMatrix.output( gene, count );
                } else
                    System.out.println( "Gene doesn't exist" );
            }
        } catch ( IOException ioe ) {
            System.out.println( "IO error:" + ioe );
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see ubic.gemma.util.AbstractCLI#doWork(java.lang.String[])
     */
    @Override
    protected Exception doWork( String[] args ) {
        // GraphVisualization graphVisualization1 = new GraphVisualization(new Object[0]);
        // graphVisualization1.run();
        // if(1== 1) return null;
        Exception err = processCommandLine( args );
        if ( err != null ) {
            return err;
        }
        try {
            eeService = this.getBean( ExpressionExperimentService.class );
            geneService = this.getBean( GeneService.class );
            goService = this.getBean( GeneOntologyService.class );
            TaxonService taxonService = this.getBean( TaxonService.class );
            taxon = taxonService.findByCommonName( taxonName );
            if ( taxon == null ) {
                return new IllegalArgumentException( "The input species couldn't be found: " + taxonName );
            }
            StopWatch watch = new StopWatch();

            // load the link matrix
            if ( this.writeLinkMatrix ) {
                watch.start();
                linkMatrix = new LinkMatrix( taxon );
                linkMatrix.setGeneService( geneService );
                linkMatrix.setEEService( eeService );
                linkMatrix.setGoService( goService );
                try {
                    linkMatrix.toFile( this.matrixFile, this.eeMapFile );
                } catch ( IOException e ) {
                    log.info( "Couldn't save the results into the files " );
                    return e;
                }
                log.info( "Spent " + watch.getTime() / 1000 + "s to generate link matrix" );
            } else {
                watch.start();
                try {
                    linkMatrix = new LinkMatrix( this.matrixFile, this.eeMapFile, eeService, geneService, goService );
                } catch ( IOException e ) {
                    log.info( "Couldn't load the data from the files " );
                    return e;
                }
                watch.stop();
                log.info( "Spent " + watch.getTime() / 1000 + "s to load the data matrix" );
            }
            System.err.println( "Finish Loading!" );
            watch.reset();
            watch.start();

            /**
             * FIXME make this a command line option.
             */
            int supportThreshold = 6;

            LinkGraphClustering clustering = new LinkGraphClustering( supportThreshold, linkMatrix );
            // clustering.testSerilizable();
            if ( this.writeClusteringTree ) {
                clustering.run();
                clustering.saveToFile( this.treeFile );
            } else {
                clustering.readTreeFromFile( this.treeFile );
            }
            ObjectArrayList savedClusters = clustering.selectClustersToSave( 20 );
            GraphViewer gviewer = new GraphViewer( savedClusters, true, linkMatrix );
            gviewer.run();

            ObjectArrayList leafNodes = new ObjectArrayList();
            // for(int i = 0; i < savedClusters.size(); i++){
            // //if(i < 3) continue;
            // TreeNode clusterRoot = (TreeNode) savedClusters.get(i);
            // leafNodes.clear();
            // LinkGraphClustering.collectTreeNodes(leafNodes, new ObjectArrayList(), clusterRoot);
            // System.err.println("Cluster"+(i+1)+"_" + leafNodes.size() +" :");
            // GraphVisualization graphVisualization = new GraphVisualization(leafNodes.toList().toArray());
            // if(i == 6 || i == 7)
            // graphVisualization.run();
            // }

            // Select clusters for frequent linkset finder
            TreeNode testNode = clustering.selectClusterWithMaximalBits( supportThreshold );
            leafNodes.clear();
            // testNode = clustering.selectMaximalCluster();
            LinkGraphClustering.collectTreeNodes( leafNodes, new ObjectArrayList(), testNode );
            GraphViewer gviewer1 = new GraphViewer( leafNodes, false, linkMatrix );
            gviewer1.run();
            FrequentLinkSetFinder freFinder = new FrequentLinkSetFinder( supportThreshold, linkMatrix );
            freFinder.find( leafNodes );
            watch.stop();
            log.info( "Spend " + watch.getTime() / 1000 + " to Generated " + FrequentLinkSetFinder.nodeNum + " nodes" );
            /*
             * MetaLinkFinder.saveLinkMatrix("linkMatrix.txt", 6); System.err.println( "Output some stats" );
             * MetaLinkFinder.outputStat(); interactiveQuery();
             */
        } catch ( Exception e ) {
            log.error( e );
            return e;
        }
        return null;
    }

    /**
     * @param args
     */
    public static void main( String[] args ) {
        MetaLinkFinderCli linkFinderCli = new MetaLinkFinderCli();
        StopWatch watch = new StopWatch();
        watch.start();
        try {
            Exception ex = linkFinderCli.doWork( args );
            if ( ex != null ) {
                ex.printStackTrace();
            }
            watch.stop();
            log.info( watch.getTime() );
        } catch ( Exception e ) {
            throw new RuntimeException( e );
        }
    }

    /* (non-Javadoc)
     * @see ubic.gemma.util.AbstractCLI#getCommandName()
     */
    @Override
    public String getCommandName() {
        // TODO Auto-generated method stub
        return null;
    }

}
