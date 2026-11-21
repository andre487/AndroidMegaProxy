// corebench runs the production core and local probes for the isolated Linux benchmark.
package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"math"
	"net"
	"os"
	"os/signal"
	"sort"
	"sync"
	"syscall"
	"time"

	"dev.megaproxy/native/mobile"
	"golang.org/x/sys/unix"
)

// The proxy has a connected veth route outside the benchmark destination's TUN route.
// Android's socket protection is therefore unnecessary in this isolated Linux namespace.
type protector struct{}

func (protector) Protect(int) bool { return true }

func runCore(mtu int, configPath string) error {
	file, err := os.OpenFile("/dev/net/tun", os.O_RDWR, 0)
	if err != nil {
		return err
	}
	defer file.Close()
	request, err := unix.NewIfreq("bench0")
	if err != nil {
		return err
	}
	request.SetUint16(unix.IFF_TUN | unix.IFF_NO_PI)
	if err := unix.IoctlIfreq(int(file.Fd()), unix.TUNSETIFF, request); err != nil {
		return err
	}
	config, err := os.ReadFile(configPath)
	if err != nil {
		return err
	}
	if err := mobile.Start(int(file.Fd()), mtu, string(config), protector{}, nil); err != nil {
		return err
	}
	defer mobile.Stop()
	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGINT)
	defer signal.Stop(stop)
	fmt.Println("READY")
	<-stop
	return nil
}

func serve(address string) error {
	tcp, err := net.Listen("tcp", address)
	if err != nil {
		return err
	}
	defer tcp.Close()
	udp, err := net.ListenPacket("udp", address)
	if err != nil {
		return err
	}
	defer udp.Close()
	failures := make(chan error, 2)
	go func() {
		for {
			conn, err := tcp.Accept()
			if err != nil {
				failures <- err
				return
			}
			go func() {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	go func() {
		buffer := make([]byte, 65535)
		for {
			n, peer, err := udp.ReadFrom(buffer)
			if err == nil {
				_, err = udp.WriteTo(buffer[:n], peer)
			}
			if err != nil {
				failures <- err
				return
			}
		}
	}()
	fmt.Println("READY")
	return <-failures
}

type probeResult struct {
	Count     int        `json:"count"`
	Seconds   float64    `json:"seconds"`
	PerSecond float64    `json:"operations_per_second"`
	LatencyMS [3]float64 `json:"latency_ms_p50_p95_p99"`
}

func percentiles(samples []time.Duration) [3]float64 {
	sort.Slice(samples, func(i, j int) bool { return samples[i] < samples[j] })
	var result [3]float64
	if len(samples) == 0 {
		return result
	}
	for i, fraction := range []float64{.50, .95, .99} {
		result[i] = float64(samples[int(math.Ceil(fraction*float64(len(samples))))-1]) / float64(time.Millisecond)
	}
	return result
}

func probe(address, network string, duration time.Duration, streams int, reconnect bool) error {
	if duration <= 0 || streams < 1 || (network != "tcp" && network != "udp") || (reconnect && network != "tcp") {
		return errors.New("invalid probe settings")
	}
	var lock sync.Mutex
	var workers sync.WaitGroup
	var samples []time.Duration
	var failure error
	start := time.Now()
	end := start.Add(duration)
	for range streams {
		workers.Add(1)
		go func() {
			defer workers.Done()
			var local []time.Duration
			var conn net.Conn
			defer func() {
				if conn != nil {
					_ = conn.Close()
				}
				lock.Lock()
				samples = append(samples, local...)
				lock.Unlock()
			}()
			payload := bytes.Repeat([]byte{0xa5}, 64)
			reply := make([]byte, len(payload))
			for time.Now().Before(end) {
				began := time.Now()
				var err error
				if conn == nil {
					conn, err = net.DialTimeout(network, address, 3*time.Second)
				}
				if err == nil {
					err = conn.SetDeadline(time.Now().Add(3 * time.Second))
				}
				if err == nil {
					var n int
					n, err = conn.Write(payload)
					if err == nil && n != len(payload) {
						err = io.ErrShortWrite
					}
				}
				if err == nil {
					_, err = io.ReadFull(conn, reply)
				}
				if err == nil && !bytes.Equal(payload, reply) {
					err = errors.New("echo payload mismatch")
				}
				if err != nil {
					lock.Lock()
					failure = err
					lock.Unlock()
					return
				}
				local = append(local, time.Since(began))
				if reconnect {
					_ = conn.Close()
					conn = nil
				}
			}
		}()
	}
	workers.Wait()
	if failure != nil {
		return failure
	}
	elapsed := time.Since(start).Seconds()
	return json.NewEncoder(os.Stdout).Encode(probeResult{len(samples), elapsed, float64(len(samples)) / elapsed, percentiles(samples)})
}

func main() {
	mode := flag.String("mode", "core", "core, echo or probe")
	mtu := flag.Int("mtu", 1400, "TUN MTU")
	configPath := flag.String("config", "", "private native config file (core mode)")
	address := flag.String("address", "198.19.0.1:9000", "local benchmark origin")
	network := flag.String("network", "tcp", "probe network: tcp or udp")
	duration := flag.Duration("duration", 3*time.Second, "probe duration")
	streams := flag.Int("streams", 1, "parallel probes")
	reconnect := flag.Bool("connect", false, "create a TCP connection for each probe")
	flag.Parse()
	var err error
	switch *mode {
	case "core":
		err = runCore(*mtu, *configPath)
	case "echo":
		err = serve(*address)
	case "probe":
		err = probe(*address, *network, *duration, *streams, *reconnect)
	default:
		err = errors.New("unknown mode")
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
