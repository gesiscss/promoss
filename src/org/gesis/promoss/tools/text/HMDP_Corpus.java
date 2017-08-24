package org.gesis.promoss.tools.text;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.Map.Entry;

public class HMDP_Corpus extends Corpus {

	public String groupfile;


	public int F = 0; //Number of features
	public int[] Cf; //Number of clusters for each feature
	public int[] Cfd; //Number of documents for each feature
	public int[][] Cfc; //Number of documents for each cluster (FxCf)
	public int[][] Cfcw; //Number of words for each cluster (FxCf)
	public int[][] Cfg; //Number of documents for each group (FxCg)

	public int[][][] A; //Groups and their clusters

	//public ArrayList<ArrayList<Set<Integer>>> affected_groups; //Which clusters are affected by changes in group g; F x G x ...

	//document groups (M+empty_documents.size() x F)
	//we first have the groups of the non-empty documents
	//followed by the groups of the empty documents
	public int[][] groups;


	public void readCorpusSize() {
		// Read dictionary from file
		// The file contains words, one in each row

		int line_number=0;
		Text dictText = new Text();

		String line;
		while((line = dictText.readLine(documentfile)) != null) {
			line_number++;

			String[] lineSplit = line.split(" ");
			boolean empty = true;
			for (int i=1;i<lineSplit.length;i++) {
				if (dict.contains(lineSplit[i])) {
					empty = false;
					break;
				}
			}

			if (empty) {
				empty_documents.add(line_number);
			}
			else {
				M++;
			}
		}

		//read non-empty document counts of clusters
		dictText = new Text();

		int m=0;
		line_number = 0;
		while((line = dictText.readLine(documentfile)) != null && m < Double.valueOf(M)*TRAINING_SHARE) {
			line_number++;
			if (!empty_documents.contains(line_number)) {
				m++;
				String[] lineSplit = line.split(" ");

				String groupString = lineSplit[0];
				String[] groupSplit = groupString.split(",");
				for (int f=0;f<F;f++) {
					int g = Integer.valueOf(groupSplit[f]);
					Cfg[f][g]++;
					for (int c=0;c<A[f][g].length;c++) {
						int a = A[f][g][c];
						Cfc[f][a]++;
						Cfd[f]++;
						//Cfcw[f][a]+=N[m];

					}
				}


			}
		}

		dictText.close();


	}

	public void readClusterSizeWords() {
		Cfcw=new int[F][];
		for (int f=0;f<F;f++) {
			Cfcw[f]=new int[Cf[f]];
		}

		for (int m=0;m < Double.valueOf(M)*TRAINING_SHARE;m++) {
			for (int f=0;f<F;f++) {
				int g = groups[m][f];
				for (int c=0;c<A[f][g].length;c++) {
					int a = A[f][g][c];
					Cfcw[f][a]+=N[m];
				}
			}
		}
//		for (int f=0;f<F;f++) {
//			for (int c=0;c<Cfcw[f].length;c++) {
//
//				System.out.println("f " + f + " c " + c + " " + Cfcw[f][c]);
//			}
//		}


	}

	public void readDict() {
		// Read dictionary from file
		// The file contains words, one in each row

		dict = new Dictionary();
		String line;
		if (!new File(dictfile).exists()) {

			if (!processed && documentText == null) {
				documentText = new Text();
				documentText.setLang(language);
				documentText.setStopwords(stopwords);
				documentText.setStem(stemming);
			}

			//create the dict from all the words in the document
			Text text = new Text();

			HashMap<String,Integer> hs = new HashMap<String,Integer>();

			while((line = text.readLine(documentfile))!=null){

				String[] lineSplit = line.split(" ",2);
				if (lineSplit.length >= 1) {
					lineSplit = lineSplit[1].split(" ");

					if (processed) {
						for(int i = 0; i < lineSplit.length; i++) {
							String word = lineSplit[i];
							int freq = 1;
							if (hs.containsKey(word)) {
								freq += hs.get(word);
							}

							hs.put(word,freq);

						}
					}
					else {

						documentText.setText(line);

						Iterator<String> words = documentText.getTerms();

						while(words.hasNext()) {
							String word = words.next();
							int freq = 1;
							if (hs.containsKey(word)) {
								freq += hs.get(word);
							}

							hs.put(word,freq);

						}
					}

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

		dictText.close();

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
				
				//System.out.println(line);

				String[] lineSplit = line.split(" ");

				int f = Integer.valueOf(lineSplit[0]);
				int groupID = Integer.valueOf(lineSplit[1]);

				int[] cluster = new int[lineSplit.length - 2];
				for (int i=2;i<lineSplit.length;i++) {
					cluster[i-2] = Integer.valueOf(lineSplit[i]);

					//Find out about the maximum cluster ID. The number of clusters is this ID +1
					Cf[f] = Math.max(Cf[f],cluster[i-2] + 1);
				}

				//System.out.println(groupID + " " + f + " " + A[f].length);
				
				if(A[f].length - 1 < groupID) {
					int[][] Afold = A[f];
					A[f] = new int[groupID+1][];
					System.arraycopy(Afold, 0, A[f], 0, Afold.length);
					Afold = null;
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
		
		//System.out.println("finished");

		//fill undefined groups with null
		//TODO: we have to add a mechanism for adding new groups...
		for (int f=0;f<F;f++) {
			for (int g=0;g<A[f].length;g++) {
				if (A[f][g] == null) {
					A[f][g] = new int[0];
				}
			}
		}

//		affected_groups = new ArrayList<ArrayList<Set<Integer>>>();
//		for (int f=0;f<F;f++) {
//			affected_groups.add(f, new ArrayList<Set<Integer>>());
//			for (int g=0;g<A[f].length;g++) {
//				affected_groups.get(f).add(g, new HashSet<Integer>());
//				for (int i = 0;i<A[f][g].length;i++) {
//					for (int g2=0;g2<A[f].length;g2++) {
//						for (int i2 = 0;i2<A[f][g].length;i2++) {
//							if (i2==i) {
//								affected_groups.get(f).get(g).add(g2);
//							}
//						}
//					}
//				}
//			}
//
//
//		}


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
		groups = load.readFileInt2(directory+"groups");

		if (load.readSVMlight(directory+"wordsets", this) && groups != null) {			
			return;
		}

		termIDs = new int[M][];
		termFreqs = new short[M][];

		Save saveSVMlight = new Save();

		groups = new int[M+empty_documents.size()][F];
		//Counter for the index of the groups of empty documents
		//They are added after the group information of the regular documents
		int empty_counter = 0;

		if (documentText == null) {
			documentText = new Text();
			documentText.setLang(language);
			documentText.setStopwords(stopwords);
			documentText.setStem(stemming);
		}

		String line = ""; 
		int m=0;
		int line_number = 0;
		while ((line = documentText.readLine(documentfile))!=null) {
			line_number++;
			HashMap<Integer,Short> distinctWords = new HashMap<Integer, Short>();

			String[] docSplit = line.split(" ",2);
			String[] groupString = docSplit[0].split(",");

			int[] group = new int[F];
			for (int f=0; f<F; f++) {
				group[f] = Integer.valueOf(groupString[f]);
			}

			if (!empty_documents.contains(line_number)) {

				if (docSplit.length>1) {
					if (processed) {
						String[] lineSplit2 = docSplit[1].split(" ");
						for(int i = 0; i < lineSplit2.length; i++) {
							String word = lineSplit2[i];
							if (dict.contains(word)) {
								int wordID = dict.getID(word);
								if (distinctWords.containsKey(wordID)) {
									int count = distinctWords.get(wordID);
									distinctWords.put(wordID, (short) (count+1));
								}
								else {
									distinctWords.put(wordID, (short) 1);
								}
							}

						}
					}
					else {
						documentText.setText(docSplit[1]);
						Iterator<String> words = documentText.getTerms();

						while(words.hasNext()) {
							String word = words.next();
							if (dict.contains(word)) {
								int wordID = dict.getID(word);
								if (distinctWords.containsKey(wordID)) {
									int count = distinctWords.get(wordID);
									distinctWords.put(wordID, (short) (count+1));
								}
								else {
									distinctWords.put(wordID, (short) 1);
								}
							}
						}
					}
					Set<Entry<Integer, Short>> wordset = distinctWords.entrySet();

					groups[m]=group;


					if (m % Math.round(M/50) == 0)
						System.out.print(".");
					m++;
					saveSVMlight.saveVar(wordset, directory+"wordsets");
				}
				else {
					groups[M+empty_counter]=group;
					empty_counter++;
				}

			}


		}

		System.out.println("");

		saveSVMlight.close();

		Save save = new Save();
		save.saveVar(groups, directory+"groups");
		save.close();

		documentText.close();

		readDocs();

		return;

	}


}
