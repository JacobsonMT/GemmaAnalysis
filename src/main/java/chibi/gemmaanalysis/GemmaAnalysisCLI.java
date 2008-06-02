/*
 * The Gemma project
 * 
 * Copyright (c) 2008 University of British Columbia
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import ubic.gemma.util.AbstractCLI;

/**
 * Generic command line information for Gemma. This doesn't do anything but print some help.
 * 
 * @author paul
 * @author raymond
 * @version $Id$
 */
public class GemmaAnalysisCLI {
    private static final String[] apps = { "chibi.gemmaanalysis.Gene2GeneCoexpressionResultsCli",
    "chibi.gemmaanalysis.GeneExpressionProfileWriterCLI",
    "chibi.gemmaanalysis.LinkGOAnalysisCli",
    "chibi.gemmaanalysis.LinkGOStatsCli",
    "chibi.gemmaanalysis.LinkStatisticsCLI",
    "chibi.gemmaanalysis.MetaLinkFinderCli",
    "chibi.gemmaanalysis.MicroRNAFinderCli",
    "chibi.gemmaanalysis.ProbeAlignedRegionAnalysisCLI",
    "chibi.gemmaanalysis.ProbeMapperCli",
    "chibi.gemmaanalysis.PSDCoexpressionResultsCli",
    "chibi.gemmaanalysis.RandomGenesCli",
    "chibi.gemmaanalysis.RankAnalysisCLI",
    "chibi.gemmaanalysis.ComputeGoOverlapCli"};

    /**
     * @param args
     */
    public static void main( String[] args ) {
        System.err.println( "============ GemmaAnalysis command line tools ============" );

        System.err
                .print( "You've evoked the GemmaAnalysis CLI in a mode that doesn't do anything.\n"
                        + "To operate Gemma tools, run a command like:\n\njava [jre options] -Done-jar.main.class=<classname> -jar /path/to/gemmaCLI.one-jar.jar [options]\n\n"
                        + "Here is a list of the classnames for some available tools:\n\n" );
        Arrays.sort( apps );
        for ( String a : apps ) {
            String desc = "";
            try {
                Class<?> aclazz = Class.forName( a );
                Object cliinstance = aclazz.newInstance();
                Method method = aclazz.getMethod( "getShortDesc", new Class[] {} );
                desc = ( String ) method.invoke( cliinstance, new Object[] {} );
            } catch ( ClassNotFoundException e ) {
                e.printStackTrace();
            } catch ( IllegalArgumentException e ) {
                e.printStackTrace();
            } catch ( IllegalAccessException e ) {
                e.printStackTrace();
            } catch ( InvocationTargetException e ) {
                e.printStackTrace();
            } catch ( SecurityException e ) {
                e.printStackTrace();
            } catch ( NoSuchMethodException e ) {
                e.printStackTrace();
            } catch ( InstantiationException e ) {
                e.printStackTrace();
            }

            System.err.println( a + " :\t" + desc );
        }
        System.err
                .println( "\nTo get help for a specific tool, use \n\njava -Done-jar.main.class=<classname> -jar /path/to/gemmaCLI.jar --help" );
        System.err.print( "\n" + AbstractCLI.FOOTER + "\n=========================================\n" );
    }
}