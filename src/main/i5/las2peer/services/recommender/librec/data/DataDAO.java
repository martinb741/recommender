// Copyright (C) 2014 Guibing Guo
//
// This file is part of LibRec.
//
// LibRec is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// LibRec is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with LibRec. If not, see <http://www.gnu.org/licenses/>.
//

package i5.las2peer.services.recommender.librec.data;

import java.io.BufferedWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import i5.las2peer.services.recommender.librec.util.FileIO;
import i5.las2peer.services.recommender.librec.util.Logs;
import i5.las2peer.services.recommender.librec.util.Stats;
import i5.las2peer.services.recommender.librec.util.Strings;

/**
 * A data access object (DAO) to a data file
 * 
 * @author guoguibing
 * 
 */
public abstract class DataDAO {

	// name of data file
	protected String dataName;
	// directory of data file
	private String dataDir;
	// path to data file
	protected String dataPath;
	// store rate data as {user, item, rate} matrix
	protected SparseMatrix rateMatrix;
	// store time data as {user, item, timestamp} matrix
	protected SparseMatrix timeMatrix;
	// store rate data as a sparse tensor
	private SparseTensor rateTensor;

	// is item type as user
	protected boolean isItemAsUser;
	// is first head line
	private boolean isHeadline = false;

	// data scales
	protected List<Double> ratingScale;
	// scale distribution
	protected Multiset<Double> scaleDist;

	// number of rates
	protected int numRatings;

	// user/item {raw id, inner id} map
	protected BiMap<String, Integer> userIds, itemIds;

	// inverse views of userIds, itemIds
	protected BiMap<Integer, String> idUsers, idItems;

	// time unit may depend on data sets, e.g. in MovieLens, it is unix seconds
	protected TimeUnit timeUnit;

	// minimum/maximum rating timestamp
	protected long minTimestamp, maxTimestamp;

	/**
	 * Constructor for a data DAO object
	 * 
	 * @param path
	 *            path to data file
	 * 
	 * @param userIds
	 *            user: {raw id, inner id} map
	 * @param itemIds
	 *            item: {raw id, inner id} map
	 */
	public DataDAO(String path, BiMap<String, Integer> userIds, BiMap<String, Integer> itemIds) {
		dataPath = path;

		if (userIds == null)
			this.userIds = HashBiMap.create();
		else
			this.userIds = userIds;

		if (itemIds == null)
			this.itemIds = HashBiMap.create();
		else
			this.itemIds = itemIds;

		scaleDist = HashMultiset.create();

		isItemAsUser = this.userIds == this.itemIds;
		timeUnit = TimeUnit.SECONDS;
	}

	/**
	 * Contructor for data DAO object
	 * 
	 * @param path
	 *            path to data file
	 */
	public DataDAO(String path) {
		this(path, null, null);
	}

	/**
	 * Contructor for data DAO object
	 * 
	 */
	public DataDAO(String path, BiMap<String, Integer> userIds) {
		this(path, userIds, userIds);
	}

	/**
	 * read data from the file(s) given by dataPath
	 * 
	 * @return a sparse matrix storing all the relevant data
	 */
	abstract public SparseMatrix[] readData() throws Exception;
	
	abstract public SparseMatrix[] readData(double binThold) throws Exception;
	
	abstract public SparseMatrix[] readData(int[] cols, double binThold) throws Exception;
	
	/**
	 * write the rate data to another data file given by the path {@code toPath}
	 * 
	 * @param toPath
	 *            the data file to write to
	 * @param sep
	 *            the sparator of the written data file
	 */
	public void writeData(String toPath, String sep) throws Exception {
		FileIO.deleteFile(toPath);

		List<String> lines = new ArrayList<>(1500);
		for (MatrixEntry me : rateMatrix) {
			String line = Strings.toString(new Object[] { me.row() + 1, me.column() + 1, (float) me.get() }, sep);
			lines.add(line);

			if (lines.size() >= 1000) {
				FileIO.writeList(toPath, lines, null, true);
				lines.clear();
			}
		}

		if (lines.size() > 0)
			FileIO.writeList(toPath, lines, null, true);

		Logs.debug("Data has been exported to {}", toPath);
	}

	/**
	 * Default sep=" " is adopted
	 */
	public void writeData(String toPath) throws Exception {
		writeData(toPath, " ");
	}

	/**
	 * Write rate matrix to a data file with format ".arff" which can be used by the PREA toolkit
	 * 
	 * @param relation
	 *            relation name of dataset
	 * @param toPath
	 *            data file path
	 */
	public void writeArff(String relation, String toPath) throws Exception {
		FileIO.deleteFile(toPath);

		BufferedWriter bw = FileIO.getWriter(toPath);

		bw.write("@RELATION " + relation + "\n\n");
		bw.write("@ATTRIBUTE UserId NUMERIC\n\n");
		bw.write("@DATA\n");

		StringBuilder sb = new StringBuilder();
		int count = 0;
		for (int u = 0, um = numUsers(); u < um; u++) {
			sb.append("{0 " + (u + 1));

			for (int j = 0, jm = numItems(); j < jm; j++) {
				double rate = rateMatrix.get(u, j);
				if (rate != 0)
					sb.append(", " + (j + 1) + " " + rate);

				if (j == jm - 1)
					sb.append("}\n");
			}

			if (count++ >= 500) {
				bw.write(sb.toString());
				count = 0;
				sb = new StringBuilder();
			}
		}

		if (count > 0)
			bw.write(sb.toString());

		bw.close();

		Logs.debug("Data has been exported to {}", toPath);
	}

	/**
	 * print out specifications of the dataset
	 */
	public void printSpecs() throws Exception {
		if (rateMatrix == null)
			readData();

		List<String> sps = new ArrayList<>();

		int users = numUsers();
		int items = numItems();
		int numRates = rateMatrix.size();

		sps.add(String.format("Dataset: %s", dataPath));
		sps.add("User amount: " + users + ", " + FileIO.formatSize(users));
		if (!isItemAsUser)
			sps.add("Item amount: " + items + ", " + FileIO.formatSize(items));
		sps.add("Rate amount: " + numRates + ", " + FileIO.formatSize(numRates));
		sps.add(String.format("Data density: %.4f%%", (numRates + 0.0) / users / items * 100));
		sps.add("Scale distribution: " + scaleDist.toString());

		// user/item mean
		double[] data = rateMatrix.getData();
		float mean = (float) (Stats.sum(data) / numRates);
		float std = (float) Stats.sd(data);
		float mode = (float) Stats.mode(data);
		float median = (float) Stats.median(data);

		sps.add("");
		sps.add(String.format("Average value of all ratings: %f", mean));
		sps.add(String.format("Standard deviation of all ratings: %f", std));
		sps.add(String.format("Mode of all rating values: %f", mode));
		sps.add(String.format("Median of all rating values: %f", median));

		List<Integer> userCnts = new ArrayList<>();
		int userMax = 0, userMin = Integer.MAX_VALUE;
		for (int u = 0, um = numUsers(); u < um; u++) {
			int size = rateMatrix.rowSize(u);
			if (size > 0) {
				userCnts.add(size);

				if (size > userMax)
					userMax = size;
				if (size < userMin)
					userMin = size;
			}
		}

		sps.add("");
		sps.add(String.format("Max number of ratings per user: %d", userMax));
		sps.add(String.format("Min number of ratings per user: %d", userMin));
		sps.add(String.format("Average number of ratings per user: %f", (float) Stats.mean(userCnts)));
		sps.add(String.format("Standard deviation of number of ratings per user: %f", (float) Stats.sd(userCnts)));

		if (!isItemAsUser) {
			List<Integer> itemCnts = new ArrayList<>();
			int itemMax = 0, itemMin = Integer.MAX_VALUE;
			for (int j = 0, jm = numItems(); j < jm; j++) {
				int size = rateMatrix.columnSize(j);
				if (size > 0) {
					itemCnts.add(size);

					if (size > itemMax)
						itemMax = size;
					if (size < itemMin)
						itemMin = size;
				}
			}

			sps.add("");
			sps.add(String.format("Max number of ratings per item: %d", itemMax));
			sps.add(String.format("Min number of ratings per item: %d", itemMin));
			sps.add(String.format("Average number of ratings per item: %f", (float) Stats.mean(itemCnts)));
			sps.add(String.format("Standard deviation of number of ratings per item: %f", (float) Stats.sd(itemCnts)));
		}

		Logs.info(Strings.toSection(sps));
	}

	/**
	 * print out distributions of the dataset <br/>
	 * 
	 * <ul>
	 * <li>#users (y) -- #ratings (x) (that are issued by each user)</li>
	 * <li>#items (y) -- #ratings (x) (that received by each item)</li>
	 * </ul>
	 */
	public void printDistr(boolean isWriteOut) throws Exception {
		if (rateMatrix == null)
			readData();

		// count how many users give the same number of ratings
		Multiset<Integer> numURates = HashMultiset.create();

		// count how many items recieve the same number of ratings
		Multiset<Integer> numIRates = HashMultiset.create();

		for (int r = 0, rm = rateMatrix.numRows; r < rm; r++) {
			int numRates = rateMatrix.rowSize(r);
			numURates.add(numRates);
		}

		for (int c = 0, cm = rateMatrix.numColumns; c < cm; c++) {
			int numRates = rateMatrix.columnSize(c);
			numIRates.add(numRates);
		}

		String ustrs = Strings.toString(numURates);
		String istrs = Strings.toString(numIRates);

		if (isWriteOut) {
			FileIO.writeString(FileIO.desktop + "user-distr.txt", ustrs);
			FileIO.writeString(FileIO.desktop + "item-distr.txt", istrs);
		} else {
			Logs.debug("#ratings (x) ~ #users (y): \n" + ustrs);
			Logs.debug("#ratings (x) ~ #items (y): \n" + istrs);
		}

		Logs.debug("Done!");

	}

	/**
	 * @return number of users
	 */
	public int numUsers() {
		return userIds.size();
	}

	/**
	 * @return number of items
	 */
	public int numItems() {
		return itemIds.size();
	}

	/**
	 * @return number of rates
	 */
	public int numRatings() {
		return numRatings;
	}

	/**
	 * @return number of days
	 */
	public int numDays() {
		return (int) TimeUnit.MILLISECONDS.toDays(maxTimestamp - minTimestamp);
	}

	/**
	 * @param rawId
	 *            raw user id as String
	 * @return inner user id as int
	 */
	public int getUserId(String rawId) {
		return userIds.get(rawId);
	}

	/**
	 * @param innerId
	 *            inner user id as int
	 * @return raw user id as String
	 */
	public String getUserId(int innerId) {

		if (idUsers == null)
			idUsers = userIds.inverse();

		return idUsers.get(innerId);
	}

	/**
	 * @param rawId
	 *            raw item id as String
	 * @return inner item id as int
	 */
	public int getItemId(String rawId) {
		return itemIds.get(rawId);
	}

	/**
	 * @param innerId
	 *            inner user id as int
	 * @return raw item id as String
	 */
	public String getItemId(int innerId) {

		if (idItems == null)
			idItems = itemIds.inverse();

		return idItems.get(innerId);
	}

	/**
	 * @return the path to the dataset file
	 */
	public String getDataPath() {
		return dataPath;
	}

	/**
	 * @return the rate matrix
	 */
	public SparseMatrix getRateMatrix() {
		return rateMatrix;
	}

	/**
	 * @return whether "items" are users, useful for social reltions
	 */
	public boolean isItemAsUser() {
		return isItemAsUser;
	}

	/**
	 * @return rating scales
	 */
	public List<Double> getRatingScale() {
		return ratingScale;
	}

	/**
	 * @return user {rawid, inner id} mappings
	 */
	public BiMap<String, Integer> getUserIds() {
		return userIds;
	}

	/**
	 * @return item {rawid, inner id} mappings
	 */
	public BiMap<String, Integer> getItemIds() {
		return itemIds;
	}

	/**
	 * @return name of the data file with file type extension
	 */
	public String getDataName() {
		if (dataName == null) {
			dataName = dataPath.substring(dataPath.lastIndexOf(File.separator) + 1, dataPath.lastIndexOf("."));
		}

		return dataName;
	}

	/**
	 * @return directory of the data file
	 */
	public String getDataDirectory() {
		if (dataDir == null) {
			int pos = dataPath.lastIndexOf(File.separator);
			dataDir = pos > 0 ? dataPath.substring(0, pos + 1) : "." + File.separator;
		}

		return dataDir;
	}

	/**
	 * set the time unit of the data file
	 */
	public void setTimeUnit(TimeUnit timeUnit) {
		this.timeUnit = timeUnit;
	}

	/**
	 * @return the minimum timestamp
	 */
	public long getMinTimestamp() {
		return minTimestamp;
	}

	/**
	 * @return the maximum timestamp
	 */
	public long getMaxTimestamp() {
		return maxTimestamp;
	}

	public SparseTensor getRateTensor() {
		return rateTensor;
	}

	public boolean isHeadline() {
		return isHeadline;
	}

	public void setHeadline(boolean isHeadline) {
		this.isHeadline = isHeadline;
	}
}
