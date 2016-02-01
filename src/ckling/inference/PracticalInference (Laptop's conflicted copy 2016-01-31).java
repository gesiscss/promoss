/*
 * Copyright (C) 2007 by
 * 
 * 	Christoph Carl Kling
 *	pcfst ät c-kling.de
 *  Institute for Web Science and Technologies (WeST)
 *  University of Koblenz-Landau
 *  west.uni-koblenz.de
 *
 * PCFST is a free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation; either version 2 of the License,
 * or (at your option) any later version.
 *
 * PCFST is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCFST; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.
 */

package ckling.inference;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.commons.lang.ArrayUtils;
import org.knowceans.corpus.Dictionary;
import org.knowceans.util.DirichletEstimation;
import org.knowceans.util.Gamma;
import org.knowceans.util.Pair;
import ckling.functions.ArrayTool;
import ckling.math.BasicMath;
import ckling.text.Load;
import ckling.text.Save;
import ckling.text.Text;





public class PracticalInference {

	//We have a debugging mode for checking the parameters
	public boolean debug = false;
	//number of top words returned for the topic file
	public int topk = 100;
	//Number of read docs (might repeat with the same docs)
	public int RUNS = 200;
	//Save variables after step SAVE_STEP
	public int SAVE_STEP = 10;
	public int BATCHSIZE = 100;
	public int BATCHSIZE_GROUPS = 100;
	//Tells after how many steps we update alpha_1
	public int BATCHSIZE_ALPHA = 1000;
	//Burn in phase: how long to wait till updating nkt?
	public int BURNIN = 10;
	//how many instances of a word do we need to see before we put it in the dictionary?
	//(this is only relevant when there is no dict file yet)
	public int MIN_DICT_WORDS = 1000;

	//relative size of the training set
	public double TRAINING_SHARE = 0.8;

	public static String dataset = "food";
	public static String basedirectory;

	public int M = 0; //Number of Documents
	public int C = 0; //Number of words in the corpus
	public int V = 0; //Number of distinct words in the corpus, read from dictfile
	public int F = 0; //Number of features
	public int[] Cf; //Number of clusters for each feature
	public int[] Cfd; //Number of documents for each feature
	public int[][] Cfc; //Number of documents for each cluster (FxCf)
	public int[][] Cfg; //Number of documents for each group (FxCg)
	public int[] N; //Number of Words per document
	public int T = 8; //Number of truncated topics
	public HashMap<Integer,Integer> Ncounts; //Number of documents with N words

	public int[][][] A; //Groups and their clusters

	public ArrayList<ArrayList<Set<Integer>>> affected_groups; //Which clusters are affected by changes in group g; F x G x ...

	public double alpha_0 = 0.1;

	public double alpha_1 = 0.1;

	//Dirichlet parameter for multinomials over clusters
	public double delta = 1.0;

	//Dirichlet parameter for multinomial over features
	public double[] epsilon;

	public double gamma = 1;

	//Dirichlet concentration parameter for topic-word distributions
	public double beta_0 = 0.01;

	//helping variable beta_0*V
	private double beta_0V = 0.01 * V;

	//Global topic weight (estimated)
	public double[] pi_0;

	public String dictfile;
	public String documentfile;
	public String groupfile;

	public Dictionary dict;

	
	public Text documentText;

	//sum of counts over all documents for cluster c in feature f for topic k
	public double[][][] nkfc;
	//Estimated number of times term t appeared in topic k
	public double[][] nkt;
	//Estimated number of times term t appeared in topic k in the batch
	public double[][] tempnkt;
	//Estimated number of words in topic k
	public double[] nk;

	//Estimated number of tables for word v in topic k, used to update tau
	public double[][] mkt;
	//Estimated number of tables for word v in topic k, used to update tau
	//temporal variable for estimation
	public double[][] tempmkt;


	//Topic "counts" per document
	public double[][] nmk;
	//Feature "counts" per document
	public double[][] nmf;
	//Topic-table "counts" for each document per cluster
	public double[][][][] tmkfc;

	//variational parameters for the stick breaking process - parameters for Beta distributions \hat{a} and \hat{b}
	public double[] ahat;
	public double[] bhat;


	//rho: Learning rate; rho = s / ((tau + t)^kappa);
	//recommended values: See "Online learning for latent dirichlet allocation" paper by Hoffman
	//tau = 64, K = 0.5; S = 1; Batchsize = 4096

	public int rhos = 10;
	public double rhokappa = 0.9;
	public int rhotau = 1000;

	public int rhos_document = 1;
	public double rhokappa_document = 0.9;
	public int rhotau_document = 10;

	public double rhokappa_hyper = 0.9;
	public int rhos_hyper = 5;
	public int rhotau_hyper = 100;
	//tells the number of processed words
	public int rhot = 0;
	//tells the number of the current run)
	public int rhot_step = 0;
	//tells the number of words seen in this document
	public int[] rhot_words_doc;

	//count number of words seen in the batch
	//remember that rhot counts the number of documents, not words
	public int[] batch_words;
	//count number of times the group was seen in the batch - FxG
	public int[][] rhot_group;

	/*
	 * Here we define helper variables
	 * Every feature has clusters
	 * Clusters belong to groups of connected clusters (e.g. adjacent clusters).
	 */

	//Sum over log(1-q(k,f,c)). We need this sum for calculating E[n>0] and E[n=0] 
	public double[][][] sumqfck_ge0;
	//Sum table counts for a given cluster; F x Cf x T
	public double[][][] sumqfck;
	//Sum over log(1-q(k,f,c)) for all documents in the given group g and cluster number c. We need this sum for calculating 
	//Sum of E[n>0] and E[n=0] for all documents of the group and cluster. 
	public double[][][] sumqfgc;


	//Sum over 1-q(_,f,_) for document M and feature f (approximated seat counts)
	public double[][] sumqf;
	//Batch estimate of sumqf2 for stochastic updates
	//private static double[] sumqf2temp;


	//Counter: how many observations do we have per cluster? Dimension: F x |C[f]|
	//We use this for doing batch updates of the cluster parameters and to calculate
	//the update rate \rho
	//private static int[][] rhot_cluster;


	//statistic over gamma, used to do batch-updates of clusters: sum of gamma
	public double[][][][] sumqtemp2_clusters;
	//statistic over gamma, used to do batch-updates of features: sum of gamma
	//private static double[] sumqtemp2_features;
	//statistic over gamma, used to do batch-updates of clusters: prodct of gamma-1
	public double[][][][] sumqtemp;

	//sum over document-topic table estimates \sum_{m=0}^{M} p(n_{mk} > 0)) for batch
	public double sumnmk_ge0=0.0;

	//denominator for alpha_0 estimate
	public double alpha_1_estimate_denominator;


	//Document-Table counts per topic, added: for updating hyper parameters. Dimension: M.
	//private static double sumqk;


	//Helper variable: word frequencies
	public int[] wordfreq;

	//document-words (M x doclength)
	public Set<Entry<Integer, Integer>>[] wordsets;
	//document groups (M x F)
	public int[][] groups;

	//group-cluster-topic distributions F x G x C x T excluding feature distribution
	public double[][][][] pi_kfc_noF;

	public double rhostkt_document;
	public double oneminusrhostkt_document;

	public HashSet<Integer> empty_documents = new HashSet<Integer>();
	
	public static void main (String[] args) {
		
		PracticalInference pi = new PracticalInference();

		pi.readSettings();

		System.out.println("Reading dictionary...");
		pi.readDict();		

		System.out.println("Initialising parameters...");
		pi.getParameters();

		System.out.println("Processing documents...");

		pi.readDocs();

		System.out.println("Estimating topics...");

		for (int i=0;i<pi.RUNS;i++) {

			System.out.println("Run " + i + " (alpha_0 "+pi.alpha_0+" alpha_1 "+ pi.alpha_1+ " beta_0 " + pi.beta_0 + " gamma "+pi.gamma + " delta " + pi.delta+ " epsilon " + pi.epsilon[0]);

			pi.rhot_step++;
			//get step size
			pi.rhostkt_document = pi.rho(pi.rhos_document,pi.rhotau_document,pi.rhokappa_document,pi.rhot_step);
			pi.oneminusrhostkt_document = (1.0 - pi.rhostkt_document);

			int progress = pi.M / 50;
			if (progress==0) progress = 1;
			for (int m=0;m<pi.M*pi.TRAINING_SHARE;m++) {
				if(m%progress == 0) {
					System.out.print(".");
				}

				pi.inferenceDoc(m);
			}
			System.out.println();

			pi.updateHyperParameters();


			if (pi.rhot_step%pi.SAVE_STEP==0) {
				//store inferred variables
				System.out.println("Storing variables...");
				pi.save();
			}

		}
		
		double perplexity = pi.perplexity();
		
		System.out.println("Perplexity: " + perplexity);
		
	}


	public void readSettings() {

		String dir = "/home/c/work/topicmodels/";

		if (!new File(dir).exists()) {
			dir = "/home/c/work/topicmodels/";
		}	

		basedirectory = dir+dataset+"/";
		// Folder names, files etc. - TODO should be set via args later
		dictfile = basedirectory+"words.txt";
		//textfile contains the group-IDs for each feature dimension of the document
		//and the words of the document, all seperated by space (example line: a1,a2,a3,...,aF word1 word2 word3 ... wordNm)
		documentfile = basedirectory+"texts.txt";
		//groupfile contains clus
		//cluster-IDs for each group.separated by space
		groupfile = basedirectory+"groups.txt";
	}


	public void readCorpusSize() {
		// Read dictionary from file
		// The file contains words, one in each row

		int line_number=0;
		wordfreq = new int[V];
		Text dictText = new Text();

		String line;
		while((line = dictText.readLine(documentfile)) != null) {
			line_number++;
			
			String[] lineSplit = line.split(" ");

			boolean empty = true;
			for (int i=1;i<lineSplit.length;i++) {
				if (dict.contains(lineSplit[i])) {
					empty = false;
					int wordid = dict.getID(lineSplit[i]);
					wordfreq[Integer.valueOf(wordid)]++;
					C++;
				}
			}

			if (empty) {
				empty_documents.add(line_number);
			}
			else {
				M++;


				String groupString = lineSplit[0];
				String[] groupSplit = groupString.split(",");
				for (int f=0;f<F;f++) {
					int g = Integer.valueOf(groupSplit[f]);
					Cfg[f][g]++;
					for (int c=0;c<A[f][g].length;c++) {
						int a = A[f][g][c];
						Cfc[f][a]++;
						Cfd[f]++;
					}
				}
			}


		}

	}

	public void readDict() {
		// Read dictionary from file
		// The file contains words, one in each row

		dict = new Dictionary();
		String line;
		if (!new File(dictfile).exists()) {


			//create the dict from all the words in the document
			Text text = new Text();

			HashMap<String,Integer> hs = new HashMap<String,Integer>();

			while((line = text.readLine(documentfile))!=null){

				String[] lineSplit = line.split(" ");

				for (int i=1;i<lineSplit.length;i++) {
					int freq = 1;
					if (hs.containsKey(lineSplit[i])) {
						freq += hs.get(lineSplit[i]);
					}

					hs.put(lineSplit[i],freq);
				}

			}


			text.write(dictfile, "", false);

			Set<Entry<String, Integer>> hses = hs.entrySet();
			Iterator<Entry<String, Integer>> hsit = hses.iterator();
			while(hsit.hasNext()) {
				Entry<String,Integer> e = hsit.next();
				if (e.getValue() >= MIN_DICT_WORDS && e.getKey().length() > 1) {
					text.writeLine(dictfile, e.getKey(), true);
				}
			}

		}
		Text dictText = new Text();

		while((line = dictText.readLine(dictfile)) != null) {

			dict.addWord(line);

		}

	}

	public void readGroups() {
		//initialise if not yet done
		if (A==null) {
			A = new int[F][][];
			for (int f = 0; f < F; f++) {
				A[f]=new int[0][];
			}
		}

		//initialise variable which stores the number of clusters for each feature
		Cf = new int[F];

		//get text of the groupfile
		Text grouptext = new Text();
		String line;
		if (new File(groupfile).exists()) {
			while ((line = grouptext.readLine(groupfile))!= null) {

				String[] lineSplit = line.split(" ");

				int f = Integer.valueOf(lineSplit[0]);
				int groupID = Integer.valueOf(lineSplit[1]);

				int[] cluster = new int[lineSplit.length - 2];
				for (int i=2;i<lineSplit.length;i++) {
					cluster[i-2] = Integer.valueOf(lineSplit[i]);

					//Find out about the maximum cluster ID. The number of clusters is this ID +1
					Cf[f] = Math.max(Cf[f],cluster[i-2] + 1);
				}

				if(A[f].length - 1 < groupID) {
					int[][] Afold = A[f];
					A[f] = new int[groupID+1][];
					System.arraycopy(Afold, 0, A[f], 0, Afold.length);
				}
				A[f][groupID] = cluster;			
			}

		}
		else {
			//read info about groups from text document


			while ((line = grouptext.readLine(documentfile))!= null) {
				String[] groupSplit = line.split(" ")[0].split(",");
				for (int f=0;f<F;f++) {
					int g = Integer.valueOf(groupSplit[f]);
					Cf[f] = Math.max(Cf[f], g+1);
				}
			}
			for (int f=0;f<F;f++) {
				A[f]=new int[Cf[f]][1];
				for (int g=0;g<Cf[f];g++) {
					A[f][g][0]=g;
				}
			}
		}

		grouptext.close();


		//fill undefined groups with null
		//TODO: we have to add a mechanism for adding new groups...
		for (int f=0;f<F;f++) {
			for (int g=0;g<A[f].length;g++) {
				if (A[f][g] == null) {
					A[f][g] = new int[0];
				}
			}
		}

		affected_groups = new ArrayList<ArrayList<Set<Integer>>>();
		for (int f=0;f<F;f++) {
			affected_groups.add(f, new ArrayList<Set<Integer>>());
			for (int g=0;g<A[f].length;g++) {
				affected_groups.get(f).add(g, new HashSet<Integer>());
				for (int i = 0;i<A[f][g].length;i++) {
					for (int g2=0;g2<A[f].length;g2++) {
						for (int i2 = 0;i2<A[f][g].length;i2++) {
							if (i2==i) {
								affected_groups.get(f).get(g).add(g2);
							}
						}
					}
				}
			}


		}


	}

	//set Parameters
	public void getParameters() {
		readFfromTextfile();
		System.out.println("Reading groups...");

		readGroups(); //if there is an unseen Group mentioned

		V = dict.length();


		beta_0V = beta_0 * V;

		batch_words = new int[V];

		mkt = new double[T][V];	
		tempmkt = new double[T][V];

		nk = new double[T];
		nkt = new double[T][V];	
		tempnkt = new double[T][V];	

		//count the number of documents in each group
		Cfg = new int[F][];
		for (int f=0;f<F;f++) {
			Cfg[f]=new int[A[f].length];
		}
		//count the number of documents in each feature
		Cfd=new int[F];
		//count the number of documents in each cluster
		Cfc=new int[F][];
		for (int f=0;f<F;f++) {
			Cfc[f]=new int[Cf[f]];
		}

		//read corpus size and initialise nkt / nk
		readCorpusSize();

		rhot_words_doc=new int[M];
		rhot_group = new int[F][];
		for (int f=0;f<F;f++) {
			rhot_group[f]=new int[A[f].length];
		}

		N = new int[M];

		nmk = new double[M][T];

		for (int t=0; t < V; t++) {
			for (int k=0;k<T;k++) {
				//												//Random assignments of words to topics
				//												double[] multrand = new double[T];
				//												double rest = 1.0;
				//												for (int k2=0;k2<T-1;k2++) {
				//													double rand = Math.random();
				//													multrand[k2]= rand * rest;
				//													rest *= (1.0-rand);
				//												}
				//												multrand[T-1] = rest;
				//												nkt[k][t] = wordfreq[t] * multrand[k];
				nkt[k][t]=Math.random();
				//nkt[k][t] = (Double.valueOf(C)/V * 1.0/ Double.valueOf(T)) * 0.9 + 0.1 * (0.5-Math.random()) * C/Double.valueOf(T);
				nk[k]+=nkt[k][t];

				tempmkt[k][t] = 0.0;
			}
		}


		pi_0 = new double[T];
		ahat = new double[T];
		bhat = new double[T];

		epsilon = new double[F];
		for (int f=0;f<F;f++) {
			epsilon[f] = 1.0;
		}

		pi_0[0] = 1.0 / (1.0+gamma);
		for (int i=1;i<T;i++) {
			pi_0[i]=(1.0 / (1.0+gamma)) * (1.0-pi_0[i-1]);
		}
		pi_0 = BasicMath.normalise(pi_0);

		System.out.println("Initialising count variables...");

		sumqfck_ge0 = new double[F][][];
		//rhot_cluster = new int[F][];
		//for (int f=0;f<F;f++) {
		//	rhot_cluster[f] = new int[Cf[f]];
		//}
		sumqfck = new double[F][][];
		sumqtemp2_clusters = new double[F][][][];
		//sumqtemp2_features = new double[F];
		sumqtemp = new double[F][][][];
		for (int f=0;f<F;f++) {
			sumqfck_ge0[f] = new double[Cf[f]][T];
			sumqfck[f] = new double[Cf[f]][T];
			sumqtemp2_clusters[f] = new double[A[f].length][][];
			sumqtemp[f] = new double[A[f].length][][];
			for (int g=0;g<A[f].length;g++) {
				sumqtemp2_clusters[f][g]=new double[A[f][g].length][T];
				sumqtemp[f][g]=new double[A[f][g].length][T];
			}
		}


		sumqfgc = new double[F][][];
		sumqf = new double[M][F];

		//sumqf2temp = new double[F];
		pi_kfc_noF = new double[F][][][];
		for (int f=0;f<F;f++) {
			sumqfgc[f] = new double[A[f].length][];
			pi_kfc_noF[f] = new double[A[f].length][][]; 
			for (int g=0;g<A[f].length;g++) {
				sumqfgc[f][g] = new double[A[f][g].length];
				pi_kfc_noF[f][g] = new double[A[f][g].length][T];
				for (int i=0;i<A[f][g].length;i++) {
					for (int k = 0; k < T; k++) {
						//for every group: get topic distribution of clusters and their weight 
						//(the weight of the clusters) for the group
						pi_kfc_noF[f][g][i][k] = pi_0[k]/((double)A[f][g].length);
					}
				}
			}
		}

		alpha_1_estimate_denominator = - BATCHSIZE_ALPHA*Gamma.digamma0(alpha_1);

		
		
	}


	/**
	 * Reads the number of features F from by counting the
	 * number of groups in the first line of the textfile
	 */
	public void readFfromTextfile() {
		String firstLine = Text.readLineStatic(documentfile);
		//File e.g. looks like groupID1,groupID2,groupID3,groupID4 word1 word2 word3
		F = firstLine.split(" ")[0].split(",").length;
	}

	@SuppressWarnings("unchecked")
	public void readDocs() {

		//Try to read parsed documents
		Load load = new Load();
		wordsets = load.readVarSet(basedirectory+"wordsets");
		groups = load.readFileInt2(basedirectory+"groups");
		if (wordsets!=null && groups != null) {

			for (int m=0;m<M;m++) {
				Set<Entry<Integer, Integer>> wordset = wordsets[m];
				for (Entry<Integer,Integer> e : wordset) {
					N[m]+=e.getValue();
				}		
			}
			return ;
		}
		else {
			wordsets = new Set[M];
			groups = new int[M][F];
		}

		if (documentText == null) {
			documentText = new Text();
			documentText.setLang("en");
			documentText.setStopwords(false);
			documentText.setStem(false);
		}
		String line = ""; 
		int m=0;
		int line_number = 0;
		while ((line = documentText.readLine(documentfile))!=null) {
			line_number++;
			if (!empty_documents.contains(line_number)) {
				HashMap<Integer,Integer> distinctWords = new HashMap<Integer, Integer>();

				String[] docSplit = line.split(" ",2);
				String[] groupString = docSplit[0].split(",");

				int[] group = new int[F];
				for (int f=0; f<F; f++) {
					group[f] = Integer.valueOf(groupString[f]);
				}

				if (docSplit.length>1) {
					documentText.setText(docSplit[1]);
					Iterator<String> words = documentText.getTerms();

					while(words.hasNext()) {
						String word = words.next();
						if (dict.contains(word)) {
							int wordID = dict.getID(word);
							if (distinctWords.containsKey(wordID)) {
								int count = distinctWords.get(wordID);
								distinctWords.put(wordID, count+1);
							}
							else {
								distinctWords.put(wordID, 1);
							}
						}
					}

					Set<Entry<Integer, Integer>> wordset = distinctWords.entrySet();

					if (m%100 == 0)
						System.out.println("Reading " + m);

					wordsets[m]=wordset;
					groups[m]=group;
					m++;
				}

			}
		}

		for (m=0;m<M;m++) {
			Set<Entry<Integer, Integer>> wordset = wordsets[m];
			for (Entry<Integer,Integer> e : wordset) {
				N[m]+=e.getValue();
			}
		}

		Save save = new Save();
		save.saveVar(wordsets, basedirectory+"wordsets");
		save.saveVar(groups, basedirectory+"groups");

		return;



	}

	public void inferenceDoc(int m) {

		//if (N[m]==0) return;

		Set<Entry<Integer, Integer>> wordset = wordsets[m];
		//if (wordset == null || wordset.isEmpty()) return;

		//increase counter of documents seen
		rhot++;

		int[] grouplength = new int[F];
		int[] group = groups[m];

		//Expectation(number of tables)
		double[] sumqmk = new double[T];

		//Stochastic cluster updates: tmkfc unkown (tables!)
		//-> get table counts per cluster (or estimate it)
		//Stochastic group updates: tmkfg unknown (tables in group, tells how often cluster X was chosen in group g)
		for (int f=0;f<F;f++) {

			//Number of clusters of the group
			grouplength[f] = A[f][group[f]].length;

			//Helping variable: sum log(1-qkfc) for this document, (don't mix with sumqkfc, which is the global count variable!)
			//Tells the expected total number of times topic k was _not_ seen for feature f in cluster c in the currect document

		}
		//get words in random order - slightly improves model performance. In theory, all words and documents should be drawn randomly
		//Collections.shuffle(wordset);

		//For the first run...
		//		if (rhot_step == 1) {
		//
		//					//random initial document-topic setting
		//					for (int k=0;k<T;k++) {
		//						nmk[m][k] =  Math.random();
		//					}
		//					nmk[m] = BasicMath.normalise(nmk[m]);
		//					for (int k=0;k<T;k++) {
		//						nmk[m][k] *= N[m];
		//					}
		//
		//		}
		//End of initialisation

		//probability of feature f given k
		double[][] pk_f = new double[T][F];
		//probability of feature x cluster x topic
		double[][][] pk_fck = new double[F][][];
		for (int f=0;f<F;f++) {
			pk_fck[f] = new double[grouplength[f]][];
			for (int i=0;i<grouplength[f];i++) {
				pk_fck[f][i] = new double[T];
			}
		}


		//Prior of the document-topic distribution
		//(This is a mixture of the cluster-topic distributions of the clusters of the document
		double[] topic_prior = new double[T];
		for (int f=0;f<F;f++) {
			int g = group[f];
			double sumqfgc_denominator = BasicMath.sum(sumqfgc[f][g]) + A[f][g].length*delta;
			double temp2 = (sumqf[m][f] + epsilon[f]);
			for (int i=0;i<grouplength[f];i++) {
				int a=A[f][g][i];
				double sumqfck2_denominator = BasicMath.sum(sumqfck[f][a])+ alpha_0;
				//cluster probability in group
				double temp3 = (sumqfgc[f][g][i] + delta) / (sumqfgc_denominator * sumqfck2_denominator);
				for (int k=0;k<T;k++) {
					
					//TODO: where is pi_kfc_noF? shouldnt it be used here??
					double temp = 	(sumqfck[f][a][k] + alpha_0 * pi_0[k]) * temp3;
					topic_prior[k]+=temp*temp2;
					pk_f[k][f]+=temp*temp2;
					pk_fck[f][i][k] = temp*temp2;
				}
			}
		}

		for (int k=0;k<T;k++) {
			pk_f[k] = BasicMath.normalise(pk_f[k]);
		}

		//get step size
		double rhostkt_documentNm = rhostkt_document * N[m];

		//Process words of the document
		for (Entry<Integer,Integer> e : wordset) {

			//term index
			int t = e.getKey();
			//How often doas t appear in the document?
			int termfreq = e.getValue();

			//update number of words seen
			rhot_words_doc[m]+=termfreq;
			if (rhot_step>BURNIN) {
				//increase number of words seen in that batch
				batch_words[t]+=termfreq;
			}

			//topic probabilities - q(z)
			double[] q = new double[T];
			//sum for normalisation
			double qsum = 0.0;

			for (int k=0;k<T;k++) {

				q[k] = 	//probability of topic given feature & group
						(nmk[m][k] + alpha_1*topic_prior[k])
						//probability of topic given word w
						* (nkt[k][t] + beta_0) 
						/ (nk[k] + beta_0V);

				qsum+=q[k];

			}


			//Normalise gamma (sum=1), update counts and probabilities
			for (int k=0;k<T;k++) {
				//normalise
				q[k]/=qsum;

				if ((Double.isInfinite(q[k]) || q[k]==1 || Double.isNaN(q[k]) || Double.isNaN(nmk[m][k]) ||  Double.isInfinite(nmk[m][k])) && !debug) {
					System.out.println("Error calculating gamma " + " " + "first part: " + (nmk[m][k] + alpha_1*topic_prior[k]) + " second part: " + (nkt[k][t] + beta_0) 
							/ (nk[k] + beta_0V) + " m " + m+ " " + N[m]+ " " + termfreq + " "+ Math.pow(oneminusrhostkt_document,termfreq) + " sumqmk " + sumqmk[k] + " qk " + q[k] + " nmk " + nmk[m][k] + " prior " + topic_prior[k] + " nkt " + nkt[k][t]+ " a0 " + alpha_0  + " alpha1 " + alpha_1 +" beta " + beta_0 + " betaV" + beta_0V);
					debug = true;
					//Skip this file...
					break;
				}

				//add to batch counts
				if (rhot_step>BURNIN) {
					tempnkt[k][t]+=q[k]*termfreq;
				}

				//update probability of _not_ seeing k in the current document
				sumqmk[k]+=Math.log(1.0-q[k])*termfreq;

				//in case the document contains only this word, we do not use nmk
				if (N[m] != termfreq) {

					//update document-feature-cluster-topic counts
					if (termfreq==1) {
						nmk[m][k] = oneminusrhostkt_document * nmk[m][k] + rhostkt_documentNm * q[k];
					}
					else {
						double temp = Math.pow(oneminusrhostkt_document,termfreq);
						nmk[m][k] = temp * nmk[m][k] + (1.0-temp) * N[m] * q[k];
					}

				}

			}


			if (rhot_step>BURNIN) {
				for (int k=0;k<T;k++) {
					tempmkt[k][t]+=Math.log(1.0-q[k])*termfreq;
				}
			}


		}
		//End of loop over document words


		//get probability for NOT seeing topic f to update delta
		//double[] tables_per_feature = new double[F];



		double[] topic_ge_0 = new double[T];
		for (int k=0;k<T;k++) {
			//Probability that we saw the given topic
			topic_ge_0[k] = (1.0 - Math.exp(sumqmk[k]));
		}

		double[] sumq2_features = new double[F];

		for (int f=0;f<F;f++) {

			int g = group[f];
			//increase count for that group
			rhot_group[f][g]++;

			if (rhot_step>BURNIN) {
				for (int k=0;k<T;k++) {
					//TODO add feature probability here?
					sumq2_features[f] += topic_ge_0[k] * pk_f[k][f];

				}
			}



			//update feature-counter
			for (int i=0;i<grouplength[f];i++) {

				//how often did we see this cluster already?
				//rhot_cluster[f][i]++;
				for (int k=0;k<T;k++) {
					//p(not_seeing_fik)
					sumqtemp[f][g][i][k] += Math.log(topic_ge_0[k] * pk_fck[f][i][k]/topic_prior[k]);
					sumqtemp2_clusters[f][g][i][k]+= topic_ge_0[k] * pk_fck[f][i][k]/topic_prior[k];
				}
			}

			updateClusterTopicDistribution(f,g);	

			updateTopicAndFeatureDistribution();

		}

		if (rhot_step>BURNIN) {
			for (int f=0;f<F;f++) {
				sumqf[m][f]=oneminusrhostkt_document * sumqf[m][f] +  rhostkt_document * sumq2_features[f];
			}
		}

		//sumqk+=tables_per_topic_sum;


		//take 10000 samples to estimate alpha_1
		if (rhot_step>BURNIN) {

			sumnmk_ge0+=BasicMath.sum(topic_ge_0);
			alpha_1_estimate_denominator += Gamma.digamma0(N[m]+alpha_1) -Gamma.digamma0(alpha_1);

			//We use the estimate from Sato and guess the table sum based on the batch
			if ((rhot-(M*TRAINING_SHARE*BURNIN))%BATCHSIZE_ALPHA == 0) {
				
				
				//alpha_1_estimate_denominator-= BATCHSIZE_ALPHA * Gamma.digamma0(alpha_1);
				
				//this is for preventing updates after we saw only empty documents
				if (sumnmk_ge0>0) {
								
					double rhostkt = rho(rhos_hyper,rhotau_hyper,rhokappa_hyper,(int) (rhot-(M*TRAINING_SHARE*BURNIN))/BATCHSIZE_ALPHA);
					
					//System.out.println(rhostkt +" " + sumnmk_ge0 + " "+ alpha_1_estimate_denominator);
					
					alpha_1 = (1.0-rhostkt) * alpha_1 + rhostkt * (sumnmk_ge0 / alpha_1_estimate_denominator);
				}

				//reset sumnmk_ge0
				sumnmk_ge0 = 0;
				alpha_1_estimate_denominator = 0;
			}
		}

	}


//	public void estimateGroupTopicDistribution(int f, int g) {
//
//		int grouplength = A[f][g].length;
//		for (int i=0;i<grouplength;i++) {
//
//			int a=A[f][g][i];
//
//			//We calculate the denominator for topic inference to save time
//			sumqfgc_denominator[f][g] = BasicMath.sum(sumqfgc[f][g]) + A[f][g].length*delta;
//			//We calculate the denominator for topic inference to save time
//			double sumqfck2_denominator = BasicMath.sum(sumqfck[f][a])+ alpha_0;
//
//			for (int k=0;k<T;k++) {
//
//				pi_kfc_noF[f][g][i][k] = 			
//						//Topic probability for this group
//						((sumqfck[f][a][k] + alpha_0 * pi_0[k]) / sumqfck2_denominator)
//						//cluster probability in group
//						* (sumqfgc[f][g][i] + delta) 
//						/ (sumqfgc_denominator[f][g]);
//				//we do not multiply with alpha_1 and the probability for the features
//
//				if ((Double.isNaN(pi_kfc_noF[f][g][i][k]) || Double.isInfinite(pi_kfc_noF[f][g][i][k])) && !debug) {
//					System.out.println("pi " +pi_kfc_noF[f][g][i][k] + " " + alpha_1 + " " + alpha_0 + " " + sumqfck[f][a][k] + " " + sumqfgc[f][g][i] + " " +sumqf[f] + " " + ")"+ " " + sumqfgc_denominator[f][g] + " " + sumqfck2_denominator);
//					debug = true;
//				}
//
//
//			}
//
//		}
//	}

	/**
	 * Here we do stochastic updates of the document-topic and the global feature counts
	 */
	public synchronized void updateTopicAndFeatureDistribution() {

		//We update global topic-word counts in batches (mini-batches lead to local optima)
		if (rhot%BATCHSIZE == 0) {
			//only update if we are beyond burn in phase
			if (rhot_step>BURNIN) {

				double rhostkt = rho(rhos,rhotau,rhokappa,rhot/BATCHSIZE);
				double rhostktnormC = rhostkt * (C / Double.valueOf(BasicMath.sum(batch_words)));


				nk = new double[T];
				for (int k=0;k<T;k++) {
					for (int v=0;v<V;v++) {
						double oneminusrhostkt = (1.0 - rhostkt);

						nkt[k][v] *= oneminusrhostkt;

						//update word-topic-tables for estimating tau
						mkt[k][v] *= oneminusrhostkt;
						if(!debug && Double.isInfinite(mkt[k][v])) {
							System.out.println("mkt pre " + Double.valueOf(wordfreq[v])/batch_words[v] + " " + mkt[k][v] + " " + Double.valueOf(wordfreq[v])/batch_words[v]);
							debug = true;
						}

						//we estimate the topic counts as the average q (tempnkt consists of BATCHSIZE observations)
						//and multiply this with the size of the corpus C
						if (tempnkt[k][v]>0) {

							nkt[k][v] += rhostktnormC * tempnkt[k][v];
							//estimate tables in the topic per word, we just assume that the topic-word assignment is 
							//identical for the other words in the corpus.
							mkt[k][v] += rhostkt * (1.0-Math.exp(tempmkt[k][v]*(C / Double.valueOf(BasicMath.sum(batch_words)))));
							if(!debug &&  (Double.isInfinite(tempmkt[k][v]) || Double.isInfinite(mkt[k][v]))) {
								System.out.println("mkt estimate " + tempmkt[k][v] + " " + mkt[k][v] + " " + Double.valueOf(wordfreq[v])/batch_words[v]);
								debug = true;
							}
							
							//reset batch counts
							tempnkt[k][v] = 0;
							//reset word counts in the last topic iteration
							if (k+1==T) {
								batch_words[v] = 0;
							}
						}

						nk[k] += nkt[k][v];

					}
				}
				
				//reset
				for (int k=0;k<T;k++) {
					for (int t=0;t<V;t++) {
						tempmkt[k][t] = 0.0;
					}
				}
			}
		}
	}

	/**
	 * @param f feature of the group
	 * @param g	group id
	 * 
	 *  Stochastic update of the topic counts for a given group of a feature
	 *  
	 */
	public synchronized void updateClusterTopicDistribution(int f, int g) {
		//These are the global variables...
		//sumqkfc2[f][a][k] ok
		//sumqfgc[f][group[f]][i] ok 
		//sumqfg[f][group[f]] 

		if (rhot_group[f][g] % BATCHSIZE_GROUPS == 0) {

			//calculate update rate
			double rhost_group = rho(rhos,rhotau,rhokappa,rhot_group[f][g]);
			double oneminusrho = 1.0-rhost_group;



			//sum over table counts per cluster


			int groupsize = A[f][g].length;
			for (int i=0;i<groupsize;i++) {
				int a = A[f][g][i];

				//update group-cluster-counts: how many tables do we expect to see for group i?
				sumqfgc[f][g][i]  = oneminusrho*sumqfgc[f][g][i] + rhost_group * BasicMath.sum(sumqtemp2_clusters[f][g][i]) * Double.valueOf(Cfg[f][g])/Double.valueOf(BATCHSIZE_GROUPS);

				for (int k=0;k<T;k++) {

					//update table counts for the global topic distribution:
					//-> Probability of seeing topic k once in each cluster?

					//total documents in cluster - remember that this includes documents from other groups
					int cluster_size = Cfc[f][a];
					//update the probability of seeing a table in the cluster: E(m_{f,c,k} > 0)
					sumqfck_ge0[f][a][k] = oneminusrho*sumqfck_ge0[f][a][k] + rhost_group * (1.0 - Math.exp((sumqtemp[f][g][i][k]*cluster_size)/BATCHSIZE_GROUPS));

					//update counts per cluster
					sumqfck[f][a][k] = oneminusrho*sumqfck[f][a][k] + rhost_group * (cluster_size/Double.valueOf(BATCHSIZE_GROUPS)) * sumqtemp2_clusters[f][g][i][k];

					//We have to reset the batch counts 
					sumqtemp2_clusters[f][g][i][k] = 0;
					sumqtemp[f][g][i][k] = 0;
				}



			}



			if (rhot_step > BURNIN)  {
				//Update global topic distribution
				updateGlobalTopicDistribution();
			}

//			Iterator<Integer> it = affected_groups.get(f).get(g).iterator();
//			while (it.hasNext()) {
//				int ag = it.next();
//				estimateGroupTopicDistribution(f,ag);
//			}
		}
	}

	public void updateGlobalTopicDistribution() {

		//sum over tables
		double[] sumfck = new double[T];

		//Start with pseudo-counts from the Beta prior
		for (int k=0;k<T;k++) {
			bhat[k]=gamma;
		}
		//Now add observed estimated counts


		for (int f=0;f<F;f++) {
			//A[f] holds the cluster indices for each cluster of each feature and thus gives us the 
			//number of clusters per feature by A[f].length
			for (int i=0;i<Cf[f];i++) {
				for (int k=0;k<T;k++) {
					//We estimate pi_0 by looking at the documents of each cluster of each feature.

					//OLD:
					//For each cluster, we calculate the probability that we saw topic k in one of its documents.
					//We then calculate the expected number of clusters where we saw topic k.
					//sumfck[k]+= sumqfck_ge0[f][i][k];

					//NEW:
					//Table counts like in Teh, Collapsed Variational Inference for HDP (but with 0-order Taylor approximation)
					double a0pik=alpha_0 * pi_0[k];
					sumfck[k]+=a0pik * sumqfck_ge0[f][i][k] * (Gamma.digamma0(a0pik + sumqfck[f][i][k]) - Gamma.digamma0(a0pik));
				}
			}
		}

		//now add this sum to ahat
		for (int k=0;k<T;k++) {
			ahat[k]=1.0+sumfck[k];
		}


		double[] ahat_copy = new double[T];
		System.arraycopy(ahat, 0, ahat_copy, 0, ahat.length);
		//get indices of sticks ordered by size (given by ahat)
		int[] index = ArrayTool.sortArray(ahat_copy);
		//large sticks come first, so reverse order
		ArrayUtils.reverse(index);

		int[] index_reverted = ArrayTool.reverseIndex(index);

		//bhat is the sum over the counts of all topics > k
		for (int k=0;k<T;k++) {
			int sort_index = index_reverted[k];
			for (int k2=sort_index+1;k2<T;k2++) {
				int sort_index_greater = index[k2];
				bhat[k] += sumfck[sort_index_greater];
			}
		}

		double[] pi_ = new double[T];
		//TODO: 1-pi
		for (int k=0;k<T;k++) {
			pi_[k]=ahat[k] / (ahat[k]+bhat[k]);
		}

		for (int k=0;k<T;k++) {
			pi_0[k]=pi_[k];
			int sort_index = index_reverted[k];
			for (int l=0;l<sort_index;l++) {
				int sort_index_lower = index[l];
				pi_0[k]*=(1.0-pi_[sort_index_lower]);
			}
			
		}

		//MAP estimation for gamma (Sato (6))
		double gamma_denominator = 0.0;
		for (int k=0;k<T-1;k++) {
			gamma_denominator += Gamma.digamma0(ahat[k] + bhat[k])- Gamma.digamma0(bhat[k]);
		}

		gamma = (T -1) / gamma_denominator;


	}

	public void updateHyperParameters() {

		if(rhot_step<=BURNIN) return;

		//System.out.println("Updating hyperparameters...");

		//Update alpha_0 using the table counts per cluster
		//Cf is the number of clusters per feature
		if (alpha_0 > 0.0001) {
	    int zeros = 0;
		double alpha_0_denominator = 0;
		for (int f = 0; f < F; f++) {
			for (int i = 0; i < Cf[f]; i++) {
				//sumqfck => potential number of tables
				double sum = BasicMath.sum(sumqfck[f][i]);
				if (sum > 0) {
				alpha_0_denominator += Gamma.digamma0(sum + alpha_0);
				}
				else {
					zeros++;
				}
			}
		}
		alpha_0_denominator -= (BasicMath.sum(Cf) - zeros) * Gamma.digamma0(alpha_0);
		
		//sumqfck_ge0 => number of tables

		alpha_0 = BasicMath.sum(sumqfck_ge0) / alpha_0_denominator;
		}
		
		double beta_0_denominator = 0.0;
		for (int k=0; k < T; k++) {
			//log(x-0.5) for approximating the digamma function, x >> 1 (Beal03)
			beta_0_denominator += Gamma.digamma0(nk[k]+beta_0*V);
		}
		beta_0_denominator -= T * Gamma.digamma0(beta_0*V);
		beta_0 = 0;
		for (int k=0;k<T;k++) {
			for (int t = 0; t < V; t++) {
				beta_0 += mkt[k][t];
				if (!debug && (mkt[k][t] == 0 || Double.isInfinite(mkt[k][t] ) || Double.isNaN(mkt[k][t] ))) {
					System.out.println("mkt " + k + " " + t + ": " + mkt[k][t] + " nkt: " +  nkt[k][t]);
					debug = true;
				}
			}
		}
		
		beta_0 /= beta_0_denominator;
		
		//at this point, beta is multiplied by V
		beta_0V = beta_0;

		//Correct value of beta
		beta_0 /=V;
		
//		beta_0 = DirichletEstimation.estimateAlphaLikChanging(nkt,beta_0,200);
//
//		beta_0V = beta_0 * V;


		//gamma prior Gamma(1,1), Minka
		//beta_0 = DirichletEstimation.estimateAlphaMap(nkt,nk,beta_0,1.0,1.0);

		//TODO zeta, delta: feature-choice (f), group-choice (delta)
		//For now: uniform prior!

		epsilon = DirichletEstimation.estimateAlphaLik(sumqf,epsilon);
		//
		//		for (int i=0;i<sumqfgc.length;i++) {
		//			for (int j=0;j<sumqfgc[i].length;j++) {
		//				System.out.println(i + " "+ j + " "+ Vectors.print(sumqfgc[i][j]));
		//			}
		//		}


		delta = DirichletEstimation.estimateAlphaLikChanging(sumqfgc, delta, 200);

	}


	public double rho (int s,int tau, double kappa, int t) {
		return Double.valueOf(s)/Math.pow((tau + t),kappa);
	}


	public void save () {

		Save save = new Save();
		save.saveVar(nkt, basedirectory+"nkt_"+rhot_step);
		save.close();
		save.saveVar(pi_0, basedirectory+"pi0_"+rhot_step);
		save.close();
		save.saveVar(sumqf, basedirectory+"sumqf_"+rhot_step);
		save.close();
		save.saveVar(alpha_0, basedirectory+"alpha_0_"+rhot_step);
		save.close();
		save.saveVar(alpha_1, basedirectory+"alpha_1_"+rhot_step);
		save.close();

		//We save the large document-topic file every 10 save steps, together with the perplexity
		if ((rhot_step % (SAVE_STEP *10)) == 0) {
			
			save.saveVar(perplexity(), basedirectory+"perplexity_"+rhot_step);
			
			double[][] doc_topic = new double[M][T];
			for (int m=0;m<M;m++) {
				for (int k=0;k<T;k++) {
					doc_topic[m][k]  = 0;
				}
			}
			for (int m=0;m<M;m++) {

				doc_topic[m]  = nmk[m];
				int[] group = groups[m];
				int[] grouplength = new int[F]; 
				for (int f =0; f<F;f++) {
					int g = group[f];
					grouplength[f] = A[f][g].length;
					for (int i=0;i<grouplength[f];i++) {
						for (int k=0;k<T;k++) {
							doc_topic[m][k]+=pi_kfc_noF[f][g][i][k];
						}
					}
				}
				double sum = BasicMath.sum(doc_topic[m]);
				for (int k=0;k<T;k++) {
					doc_topic[m][k]/=sum;
				}
			}
			save.saveVar(doc_topic, basedirectory+"doc_topic_"+rhot_step);
			save.close();
		}

		double[][][] feature_cluster_topics = new double[F][][];

		for (int f=0; f<F;f++) {
			feature_cluster_topics[f] = new double[Cf[f]][T];
			for (int a=0;a<Cf[f];a++) {
				double sumqfck2_denominator = BasicMath.sum(sumqfck[f][a]) + alpha_0;
				for (int k=0;k<T;k++) {
					feature_cluster_topics[f][a][k]=(sumqfck[f][a][k] + alpha_0 * pi_0[k]) / sumqfck2_denominator;
				}
			}

			save.saveVar(feature_cluster_topics[f], basedirectory+"clusters_"+f+"_"+rhot_step);
			save.close();
		}

		if (topk > V) {
			topk = V;
		}


		String[][] topktopics = new String[T*2][topk];

		for (int k=0;k<T;k++) {

			List<Pair> wordprob = new ArrayList<Pair>(); 
			for (int v = 0; v < V; v++){
				wordprob.add(new Pair(dict.getWord(v), (nkt[k][v]+beta_0)/(nk[k]+beta_0V), false));
			}
			Collections.sort(wordprob);

			for (int i=0;i<topk;i++) {
				topktopics[k*2][i] = (String) wordprob.get(i).first;
				topktopics[k*2+1][i] = String.valueOf(wordprob.get(i).second);
			}

		}
		save.saveVar(topktopics, basedirectory+"topktopics_"+rhot_step);

		save.saveVar("alpha_0 "+alpha_0+"\nalpha_1 "+ alpha_1+ "\nbeta_0 " + beta_0 + "\ngamma "+gamma, basedirectory+"others_"+rhot_step);


		//save counts for tables in clusters
		double[] sumfck = new double[T];
		for (int f=0;f<F;f++) {
			for (int i=0;i<Cf[f];i++) {
				for (int k=0;k<T;k++) {
					//NEW:
					//Table counts like in Teh, Collapsed Variational Inference for HDP (but with 0-order Taylor approximation)
					double a0pik=alpha_0 * pi_0[k];
					sumfck[k]+=a0pik * sumqfck_ge0[f][i][k] * (Gamma.digamma0(a0pik + sumqfck[f][i][k]) - Gamma.digamma0(a0pik));
				}
			}
		}
		save.saveVar(sumfck, basedirectory+"sumfck_"+rhot_step);

		double[] topic_ge0 = new double[T];
		for (int k=0;k<T;k++) {
			topic_ge0[k] = 1.0;
		}
		for (int f=0;f<F;f++) {
			for (int i=0;i<Cf[f];i++) {
				for (int k=0;k<T;k++) {
					topic_ge0[k] *= 1.0-sumqfck_ge0[f][i][k];
				}
			}
		}
		for (int k=0;k<T;k++) {
			topic_ge0[k] = 1.0-topic_ge0[k];
		}

		save.saveVar(topic_ge0, basedirectory+"topic_ge0_"+rhot_step);
		
	}

	public double perplexity () {

		int testsize = (int) TRAINING_SHARE * M;
		if (testsize == 0) return 0;

		double[][][] z = new double[testsize][][];

		int totalLength = 0;
		double likelihood = 0;
		
		for (int m = testsize; m<M; m++) {
			int doclength = wordsets[m].size();
			totalLength+=doclength;
			z[m-testsize] = new double[doclength][T];
		}
		
		int runmax = 200;
		//sample for 200 runs
		for (int RUN=0;RUN<runmax;RUN++) {
			for (int m = testsize; m<M; m++) {
				
				Set<Entry<Integer, Integer>> wordset = wordsets[m];

				int[] grouplength = new int[F];
				int[] group = groups[m];

				//Expectation(number of tables)
				double[] sumqmk = new double[T];

				for (int f=0;f<F;f++) {
					//Number of clusters of the group
					grouplength[f] = A[f][group[f]].length;
				}

				//Prior of the document-topic distribution
				//(This is a mixture of the cluster-topic distributions of the clusters of the document
				double[] topic_prior = new double[T];
				for (int f=0;f<F;f++) {
					int g = group[f];
					double sumqfgc_denominator = BasicMath.sum(sumqfgc[f][g]) + A[f][g].length*delta;
					double temp2 = (sumqf[m][f] + epsilon[f]);
					for (int i=0;i<grouplength[f];i++) {
						int a=A[f][g][i];
						double sumqfck2_denominator = BasicMath.sum(sumqfck[f][a])+ alpha_0;
						//cluster probability in group
						double temp3 = (sumqfgc[f][g][i] + delta) / (sumqfgc_denominator * sumqfck2_denominator);
						for (int k=0;k<T;k++) {
							double temp = 	(sumqfck[f][a][k] + alpha_0 * pi_0[k])	* temp3;
							topic_prior[k]+=temp*temp2;
						}
					}
				}

				//word index
				int n = 0;
				//Process words of the document
				for (Entry<Integer,Integer> e : wordset) {
					
					//remove old counts 
					for (int k=0;k<T;k++) {
						nmk[m][k] -= z[m][n][k];
					}
					
					//term index
					int t = e.getKey();
					//How often doas t appear in the document?
					int termfreq = e.getValue();

					//update number of words seen
					rhot_words_doc[m]+=termfreq;
					if (rhot_step>BURNIN) {
						//increase number of words seen in that batch
						batch_words[t]+=termfreq;
					}

					//topic probabilities - q(z)
					double[] q = new double[T];
					//sum for normalisation
					double qsum = 0.0;

					for (int k=0;k<T;k++) {

						q[k] = 	//probability of topic given feature & group
								(nmk[m][k] + alpha_1*topic_prior[k])
								//probability of topic given word w
								* (nkt[k][t] + beta_0) 
								/ (nk[k] + beta_0V);

						qsum+=q[k];

					}


					//Normalise gamma (sum=1), update counts and probabilities
					for (int k=0;k<T;k++) {
						//normalise
						q[k]/=qsum;
						z[m-testsize][n][k]=termfreq*q[k];

						if ((Double.isInfinite(q[k]) || q[k]==1 || Double.isNaN(q[k]) || Double.isNaN(nmk[m][k]) ||  Double.isInfinite(nmk[m][k])) && !debug) {
							System.out.println("Gamma is infinite " + m+ " " + N[m]+ " " + termfreq + " "+ Math.pow(oneminusrhostkt_document,termfreq) + " sumqmk " + sumqmk[k] + " qk " + q[k] + " nmk " + nmk[m][k] + " prior " + topic_prior[k] + " nkt " + nkt[k][t]+ " a0 " + alpha_0  + " a1 " + alpha_1 +" b " + beta_0);
							debug = true;
						}

						nmk[m][k]+=termfreq*q[k];

					}

					n++;
				}
			}

		}
		
		//sampling of topic-word distribution finished - now calculate the likelihood and normalise by totalLength
		for (int m = testsize; m<M; m++) {
			Set<Entry<Integer, Integer>> wordset = wordsets[m];
			
			int n=0;
			for (Entry<Integer,Integer> e : wordset) {
				
				int termfreq = e.getValue();
				//term index
				int t = e.getKey();

				double lik = 0;
				for (int k=0;k<T;k++) {
					lik +=  z[m][n][k] * (nkt[k][t] + beta_0) / (nk[k] + beta_0V);
				}
				likelihood+=termfreq * Math.log(lik);
				
				
				
				n++;
			}
			
			
		}
		
		//get perplexity
		return (Math.exp(-likelihood / Double.valueOf(totalLength)));


	}


}