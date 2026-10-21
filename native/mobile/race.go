package mobile

import (
	"context"
	"errors"
	"time"
)

// raceFirst starts alternatives 200 ms apart so a healthy first choice needs no
// duplicate request. Failed attempts release the next immediately. Callers bound
// the candidate list; all losers are cancelled and late successes are disposed.
func raceFirst[T any](ctx context.Context, count int, attempt func(context.Context, int) (T, error), dispose func(T)) (T, error) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	type result struct {
		value T
		err   error
	}
	results := make(chan result)
	start := func(index int) {
		go func() {
			value, err := attempt(ctx, index)
			select {
			case results <- result{value, err}:
			case <-ctx.Done():
				if err == nil && dispose != nil {
					dispose(value)
				}
			}
		}()
	}
	var zero T
	if err := ctx.Err(); err != nil {
		return zero, err
	}
	if count == 0 {
		return zero, errors.New("no connection candidates")
	}
	timer := time.NewTimer(200 * time.Millisecond)
	defer timer.Stop()
	started, finished := 1, 0
	start(0)
	var failures []error
	for finished < count {
		select {
		case <-ctx.Done():
			return zero, ctx.Err()
		case <-timer.C:
			if started < count {
				start(started)
				started++
				timer.Reset(200 * time.Millisecond)
			}
		case r := <-results:
			finished++
			if r.err == nil {
				if err := ctx.Err(); err != nil {
					if dispose != nil {
						dispose(r.value)
					}
					return zero, err
				}
				return r.value, nil
			}
			failures = append(failures, r.err)
			if started < count {
				start(started)
				started++
				timer.Reset(200 * time.Millisecond)
			}
		}
	}
	return zero, errors.Join(failures...)
}
