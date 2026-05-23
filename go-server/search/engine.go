// Package search — BM25 全文检索引擎
package search

import (
	"math"
	"sort"
	"strings"
	"sync"

	"server.app/model"
)

const (
	K1       = 1.2
	B        = 0.75
)

var fieldBoost = map[string]float64{
	"title":       10.0,
	"actors":      3.0,
	"director":    2.0,
	"genre":       2.0,
	"area":        1.5,
	"description": 1.0,
}

type Posting struct {
	DocID     int
	TermFreq  int
	FieldType string
}

type DocEntry struct {
	Movie      *model.MovieInfo
	TotalTerms int
}

type Engine struct {
	mu       sync.RWMutex
	docs     []DocEntry
	inverted map[string][]Posting
	totalDoc int
}

func NewEngine() *Engine {
	return &Engine{
		inverted: make(map[string][]Posting),
	}
}

func (e *Engine) BuildIndex(movies []model.MovieInfo) {
	e.mu.Lock()
	defer e.mu.Unlock()

	e.docs = make([]DocEntry, len(movies))
	e.inverted = make(map[string][]Posting)
	e.totalDoc = len(movies)

	for i, m := range movies {
		m := m
		e.docs[i] = DocEntry{Movie: &m}
		fields := map[string]string{
			"title":       m.Title,
			"actors":      m.Actors,
			"director":    m.Director,
			"genre":       m.Genre,
			"area":        m.Area,
			"description": m.Description,
		}
		totalTerms := 0
		for fieldType, text := range fields {
			tokens := tokenize(text)
			freqs := make(map[string]int)
			for _, t := range tokens {
				freqs[t]++
			}
			for token, freq := range freqs {
				e.inverted[token] = append(e.inverted[token], Posting{
					DocID: i, TermFreq: freq, FieldType: fieldType,
				})
			}
			totalTerms += len(tokens)
		}
		e.docs[i].TotalTerms = totalTerms
	}
}

func (e *Engine) Search(query string, filters map[string]string, page, pageSize int) []model.SearchResult {
	e.mu.RLock()
	defer e.mu.RUnlock()

	if e.totalDoc == 0 {
		return nil
	}

	tokens := tokenize(query)
	if len(tokens) == 0 {
		return nil
	}

	// BM25 打分
	scores := make(map[int]float64)
	avgDocLen := avgLen(e.docs)

	for _, token := range tokens {
		postings, ok := e.inverted[token]
		if !ok {
			continue
		}
		idf := math.Log(1 + (float64(e.totalDoc)-float64(len(postings))+0.5)/(float64(len(postings))+0.5))

		for _, p := range postings {
			doc := e.docs[p.DocID]
			docLen := float64(doc.TotalTerms)
			boost := fieldBoost[p.FieldType]
			if boost == 0 {
				boost = 0.5
			}
			tf := float64(p.TermFreq)
			bmscore := idf * (tf * (K1 + 1)) / (tf + K1*(1-B+B*docLen/avgDocLen))
			scores[p.DocID] += bmscore * boost
		}
	}

	// 过滤
	var docIDs []int
	for id := range scores {
		if filters != nil {
			doc := e.docs[id].Movie
			match := true
			for k, v := range filters {
				switch k {
				case "source":
					if doc.Source != v {
						match = false
					}
				case "type":
					if doc.Type != v {
						match = false
					}
				case "genre":
					if !strings.Contains(doc.Genre, v) {
						match = false
					}
				case "year":
					y := 0
					if v != "" {
						y = parseInt(v)
					}
					if y > 0 && doc.Year != y {
						match = false
					}
				}
			}
			if !match {
				delete(scores, id)
				continue
			}
		}
		docIDs = append(docIDs, id)
	}

	sort.Slice(docIDs, func(i, j int) bool {
		return scores[docIDs[i]] > scores[docIDs[j]]
	})

	start := (page - 1) * pageSize
	if start >= len(docIDs) {
		return nil
	}
	end := start + pageSize
	if end > len(docIDs) {
		end = len(docIDs)
	}

	var results []model.SearchResult
	for _, id := range docIDs[start:end] {
		results = append(results, model.SearchResult{
			Rank:  int(scores[id]),
			Movie: *e.docs[id].Movie,
		})
	}
	return results
}

func (e *Engine) Autocomplete(prefix string, limit int) []string {
	e.mu.RLock()
	defer e.mu.RUnlock()

	if prefix == "" {
		return nil
	}
	prefix = strings.ToLower(prefix)
	set := make(map[string]bool)

	for _, doc := range e.docs {
		title := strings.ToLower(doc.Movie.Title)
		if strings.HasPrefix(title, prefix) {
			set[doc.Movie.Title] = true
			if len(set) >= limit {
				break
			}
		}
	}

	var result []string
	for t := range set {
		result = append(result, t)
	}
	sort.Strings(result)
	if len(result) > limit {
		result = result[:limit]
	}
	return result
}

func tokenize(text string) []string {
	if text == "" {
		return nil
	}
	text = strings.ToLower(text)
	// 简单中英文分词：按非字母数字字符分割
	var tokens []string
	var buf strings.Builder
	for _, r := range text {
		if isTokenChar(r) {
			buf.WriteRune(r)
		} else {
			if buf.Len() > 0 {
				tokens = append(tokens, buf.String())
				buf.Reset()
			}
		}
	}
	if buf.Len() > 0 {
		tokens = append(tokens, buf.String())
	}
	return tokens
}

func isTokenChar(r rune) bool {
	return (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') ||
		(r >= '0' && r <= '9') || (r >= 0x4e00 && r <= 0x9fff) ||
		r == '-' || r == '_'
}

func avgLen(docs []DocEntry) float64 {
	if len(docs) == 0 {
		return 1
	}
	total := 0
	for _, d := range docs {
		total += d.TotalTerms
	}
	return float64(total) / float64(len(docs))
}

func parseInt(s string) int {
	n := 0
	for _, r := range s {
		if r >= '0' && r <= '9' {
			n = n*10 + int(r-'0')
		}
	}
	return n
}
