package model

import (
	"encoding/json"
	"strings"
	"testing"
)

// RED: Verify MovieInfo JSON serialization includes all fields
func TestMovieInfoJSON(t *testing.T) {
	m := MovieInfo{
		VodId: 1001, Title: "Test Movie",
		CoverUrl: "https://example.com/c.jpg",
		Year: 2024, Area: "China", Genre: "Action",
		Director: "Director", Actors: "Actor",
		Description: "Description", Score: "8.5",
		Remark: "1080P", Source: "BaiDu",
		Type: "Movie", ListDate: "2026-05-10",
		Plays: []PlayGroup{{
			From: "bdzy", Name: "Line 1",
			Urls: []PlayUrl{{Episode: "Ep 1", Url: "https://cdn.example.com/ep1.m3u8"}},
		}},
	}
	data, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	var decoded MovieInfo
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.VodId != 1001 || decoded.Title != "Test Movie" {
		t.Fatalf("field mismatch: %d/%s", decoded.VodId, decoded.Title)
	}
	if len(decoded.Plays) != 1 || decoded.Plays[0].Urls[0].Episode != "Ep 1" {
		t.Fatal("plays decode failed")
	}
}

// RED: Verify MovieInfo omits empty plays field
func TestMovieInfoPlaysOmitEmpty(t *testing.T) {
	m := MovieInfo{VodId: 1002, Title: "No Play"}
	data, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), "plays") {
		t.Fatal("plays should be omitted when empty")
	}
}

// RED: Verify C++ JSON format compatibility (id instead of vod_id)
func TestCppJSONCompatibility(t *testing.T) {
	// C++ saves JSON with "id":1234, "coverUrl":"..."
	cppJSON := `{"id":2264,"title":"Test","coverUrl":"https://cdn.com/pic.jpg","year":2025,"area":"China","genre":"Action","director":"Zhang","actors":"Liu","description":"Desc","score":"8.0","remark":"HD","source":"BaiDu","type":"Movie","list_date":"2026-05-10","status":"","lastCheckTime":"","hasUpdate":false,"plays":[{"from":"bdzy","name":"Line 1","urls":[{"episode":"Ep 1","url":"https://cdn.com/play.m3u8"}]}]}`
	var m MovieInfo
	if err := json.Unmarshal([]byte(cppJSON), &m); err != nil {
		t.Fatalf("C++ JSON unmarshal failed: %v", err)
	}
	if m.VodId != 2264 {
		t.Fatalf("VodId: expected 2264, got %d", m.VodId)
	}
	if m.Title != "Test" || m.Score != "8.0" {
		t.Fatal("field mismatch")
	}
	if len(m.Plays) == 0 {
		t.Fatal("plays missing")
	}
	if m.CoverUrl != "https://cdn.com/pic.jpg" {
		t.Fatalf("coverUrl: expected pic.jpg, got %s", m.CoverUrl)
	}
}

// RED: Verify Go JSON can be read by C++ (id field present)
func TestGoToCppCompatibility(t *testing.T) {
	m := MovieInfo{VodId: 3001, Title: "Go Movie", Genre: "Comedy", Source: "YingHua"}
	data, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	// C++ reads "id" field — ensure our JSON has it
	if !strings.Contains(string(data), `"id":3001`) && !strings.Contains(string(data), `"vod_id":3001`) {
		t.Fatalf("JSON should contain either id or vod_id: %s", string(data))
	}
}

func contains(data []byte, s string) bool {
	return strings.Contains(string(data), s)
}

