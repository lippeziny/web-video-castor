package browse

import (
	"context"
	"errors"
	"io"
	"sync/atomic"
	"testing"

	"github.com/stupside/castor/cmd/castor/internal/browse/tmdb"
)

type failing struct {
	shelf
	posters, details atomic.Int32
}

func (f *failing) Poster(context.Context, string) (io.ReadCloser, error) {
	f.posters.Add(1)
	return nil, errors.New("down")
}

func (f *failing) Details(context.Context, tmdb.Media, int) (*tmdb.Details, error) {
	f.details.Add(1)
	return nil, errors.New("down")
}

func TestAFailedPosterOrDetailFetchIsNotRetriedOnTheNextHover(t *testing.T) {
	c := &failing{}
	in := newInspector(t.Context(), c)
	r := tmdb.SearchResult{ID: 1, MediaType: tmdb.MediaMovie, PosterPath: "/p.jpg"}

	for range 3 {
		if cmd := in.loadPoster(r); cmd != nil {
			in.update(cmd(), &r)
		}
		if cmd := in.loadDetails(r); cmd != nil {
			in.update(cmd(), &r)
		}
	}
	if c.posters.Load() != 1 || c.details.Load() != 1 {
		t.Fatalf("fetched the poster %d and details %d times, want once each", c.posters.Load(), c.details.Load())
	}
}
