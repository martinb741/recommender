package i5.las2peer.services.recommender.librec.intf;

import java.util.Map;

import com.google.common.collect.Table;

import i5.las2peer.services.recommender.librec.data.ItemContext;
import i5.las2peer.services.recommender.librec.data.RatingContext;
import i5.las2peer.services.recommender.librec.data.SparseMatrix;
import i5.las2peer.services.recommender.librec.data.UserContext;

/**
 * Generic recommenders where contextual information is used. The context can be user-, item- and rating-related.
 * 
 * @author guoguibing
 * 
 */
public class ContextRecommender extends IterativeRecommender {

	// {user, user-context}
	protected static Map<Integer, UserContext> userContexts;
	// {item, item-context}
	protected static Map<Integer, ItemContext> itemContexts;
	// {user, item, rating-context}
	protected static Table<Integer, Integer, RatingContext> ratingContexts;

	// initialization
	static {

		// read context information here
	}

	public ContextRecommender(SparseMatrix trainMatrix, SparseMatrix testMatrix, int fold) {
		super(trainMatrix, testMatrix, fold);
	}
	
}
