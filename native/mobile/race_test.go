package mobile

import (
	"context"
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

func TestRaceFirstReturnsFastAlternativeAndDisposesLateSuccess(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	firstStarted, disposed := make(chan struct{}), make(chan int, 1)
	value, err := raceFirst(ctx, 2, func(ctx context.Context, index int) (int, error) {
		if index == 0 {
			close(firstStarted)
			<-ctx.Done()
			return 10, nil
		}
		<-firstStarted
		return 20, nil
	}, func(value int) { disposed <- value })
	if err != nil || value != 20 {
		t.Fatalf("value=%d err=%v", value, err)
	}
	select {
	case value := <-disposed:
		if value != 10 {
			t.Fatal(value)
		}
	case <-ctx.Done():
		t.Fatal("late success leaked")
	}
}

func TestRaceFirstHealthyPrimaryNeedsNoAlternatives(t *testing.T) {
	var calls atomic.Int32
	value, err := raceFirst(context.Background(), 5, func(context.Context, int) (int, error) { calls.Add(1); return 7, nil }, nil)
	if err != nil || value != 7 || calls.Load() != 1 {
		t.Fatalf("value=%d err=%v calls=%d", value, err, calls.Load())
	}
}

func TestRaceFirstErrorsAndCancellation(t *testing.T) {
	first, second := errors.New("first"), errors.New("second")
	_, err := raceFirst(context.Background(), 2, func(_ context.Context, index int) (int, error) { return 0, []error{first, second}[index] }, nil)
	if !errors.Is(err, first) || !errors.Is(err, second) {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = raceFirst(ctx, 2, func(ctx context.Context, _ int) (int, error) { <-ctx.Done(); return 0, ctx.Err() }, nil)
	if !errors.Is(err, context.Canceled) {
		t.Fatal(err)
	}
}
