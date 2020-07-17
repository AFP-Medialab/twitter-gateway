package com.afp.medialab.weverify.social.twint;

import static org.elasticsearch.index.query.QueryBuilders.existsQuery;
import static org.elasticsearch.index.query.QueryBuilders.matchQuery;
import static org.elasticsearch.index.query.QueryBuilders.rangeQuery;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import javax.transaction.Transactional;

import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.TermsQueryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.SearchHitsIterator;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import com.afp.medialab.weverify.social.model.CollectRequest;
import com.afp.medialab.weverify.social.model.twint.TwintModel;

@Service
@Transactional
public class ESOperations {

	@Autowired
	private ElasticsearchOperations esOperation;
	
	@Autowired
	private TwittieProcessing twittieProcessing;

	// bulk number of request
	private int bulkLimit = 1000;

	// private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd
	// HH:mm:ss");

	private static Logger Logger = LoggerFactory.getLogger(ESOperations.class);

	/**
	 * Search query with essid
	 * 
	 * @param essid
	 * @param start
	 * @param end
	 * @throws IOException
	 */
	public void enrichWithTweetie(String essid) throws IOException {
		BoolQueryBuilder builder = QueryBuilders.boolQuery();
		builder.must(matchQuery("essid", essid));
		builder.mustNot(existsQuery("wit"));
		NativeSearchQuery searchQuery = new NativeSearchQueryBuilder().withQuery(builder)
				.withPageable(PageRequest.of(0, 10)).build();
		SearchHitsIterator<TwintModel> stream = esOperation.searchForStream(searchQuery, TwintModel.class);
		List<TwintModel> model = new ArrayList<TwintModel>();
		while (stream.hasNext()) {
			model.add(stream.next().getContent());
		}
		stream.close();
		indexWordsSubList(model);
	}

	/**
	 * Get Twint request from ES that match with the current collectRequest and with
	 * tweets without wit fields.
	 * 
	 * @deprecated
	 * @param collectRequest
	 * @return
	 * @throws IOException
	 */
	public List<TwintModel> enrichWithTweetie(CollectRequest collectRequest) throws IOException {

		BoolQueryBuilder builder = searchQueryBuilder(collectRequest);

		NativeSearchQuery searchQuery = new NativeSearchQueryBuilder().withQuery(builder)
				.withPageable(PageRequest.of(0, 10)).build();
		SearchHitsIterator<TwintModel> stream = esOperation.searchForStream(searchQuery, TwintModel.class);

		List<TwintModel> model = new ArrayList<TwintModel>();
		while (stream.hasNext()) {
			model.add(stream.next().getContent());
		}
		stream.close();
		return model;
	}

	/**
	 * Build ES query for current search
	 * 
	 * @param collectRequest
	 * @return
	 */
	private BoolQueryBuilder searchQueryBuilder(CollectRequest collectRequest) {
		// String keywordQuery =
		// TwintRequestGenerator.getInstance().generateSearch(collectRequest);
		BoolQueryBuilder builder = QueryBuilders.boolQuery();
		// builder.must(matchPhraseQuery("search", keywordQuery));
		builder.mustNot(existsQuery("wit"));
		Set<String> users = collectRequest.getUserList();
		if (users != null && !users.isEmpty()) {
			QueryBuilder userQueryBuilder = new TermsQueryBuilder("screen_name", users);
			builder.must(userQueryBuilder);
		}
		if (!collectRequest.isDisableTimeRange()) {
			long fromEpoch = collectRequest.getFrom().toInstant().getEpochSecond();
			long untilEpoch = collectRequest.getUntil().toInstant().getEpochSecond();
			// String from = dateFormat.format(collectRequest.getFrom());
			// String until = dateFormat.format(collectRequest.getUntil());
			// Logger.debug("search from {} to {}", from, until);
			Logger.debug("search from {} to {}", fromEpoch, untilEpoch);

			builder.filter(rangeQuery("datetimestamp").format("epoch_second").gte(fromEpoch).lte(untilEpoch));
		}
		return builder;
	}

	/**
	 * Get the latest tweet extracted for this session. (Oldest tweet is always
	 * searched)
	 *
	 * @param request
	 * @param session
	 * @return
	 */
	public Date findWhereIndexingStopped(CollectRequest request) {

		QueryBuilder builder = searchQueryBuilder(request);

		NativeSearchQuery searchQuery = new NativeSearchQueryBuilder().withQuery(builder).build()
				.addSort(Sort.by("datetimestamp").ascending()).setPageable(PageRequest.of(0, 1));
		final SearchHits<TwintModel> hits = esOperation.search(searchQuery, TwintModel.class);

		if (hits.isEmpty()) {
			Logger.error("No tweets found");
			return null;
		}
		SearchHit<TwintModel> twintModelHit = hits.getSearchHit(0);
		TwintModel twintModel = twintModelHit.getContent();
		long datetimestamp = twintModel.getDatetimestamp();
		Instant instant = Instant.ofEpochSecond(datetimestamp);
		Date date = Date.from(instant);
		return date;

	}

	/**
	 * 
	 * @param tms
	 * @throws IOException
	 */
	private void indexWordsSubList(List<TwintModel> tms) throws IOException {
		if (tms.isEmpty())
			return;
		int listSize = tms.size();
		Logger.debug("List size {}", listSize);
		int nbSubList = listSize / bulkLimit;
		Collection<Future<String>> results = new ArrayList<>();
		Logger.debug("Nb List {}", nbSubList);
		for (int i = 0; i <= nbSubList; i++) {
			int fromIndex = i * bulkLimit;
			int toIndex = fromIndex + bulkLimit;
			if (toIndex > listSize) {
				toIndex = listSize;
			}
			Logger.debug("index from {} to {}", fromIndex, toIndex);
			List<TwintModel> subList = tms.subList(fromIndex, toIndex);
			Logger.debug("sublist size {}", subList.size());
			results.add(twittieProcessing.indexWordsObj(subList));
		}
		CompletableFuture.allOf(results.toArray(new CompletableFuture<?>[results.size()])).join();
		
	}

	

}
