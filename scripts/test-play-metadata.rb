# Offline contract check: execute the lane with a fake upload action, never Fastlane or Google APIs.
require "json"
require "open3"
require "tmpdir"
require "fileutils"

module UI
  def self.user_error!(message)
    raise ArgumentError, message
  end

  def self.message(_message); end
end

class MetadataLaneCheck
  attr_reader :uploads

  def initialize(fastfile, filename)
    @uploads = []
    instance_eval(fastfile, filename)
  end

  def default_platform(_value); end
  def opt_out_usage; end
  def ensure_bundle_exec; end
  def ruby_version(_value); end
  def desc(_value); end

  def platform(_value)
    yield
  end

  def lane(name, &body)
    define_singleton_method(name, &body)
  end
  alias private_lane lane

  def upload_to_play_store(**options)
    files = Dir.glob(File.join(options.fetch(:metadata_path), "*", "*.txt"))
    @uploads << [options, files.to_h { |path| [path.split("/").last(2).join("/"), File.read(path)] }]
  end
end

def assert(condition, message)
  raise message unless condition
end

def rejects
  yield
  raise "Expected rejection"
rescue ArgumentError
  # Expected validation failure before upload.
end

fastfile = File.read(File.expand_path("../fastlane/Fastfile", __dir__))
previous_key = ENV["SUPPLY_JSON_KEY_DATA"]
ENV["SUPPLY_JSON_KEY_DATA"] = JSON.generate(type: "service_account", client_email: "test@example.invalid", private_key: "fake", token_uri: "https://example.invalid")
begin
  Dir.mktmpdir("play-metadata-check-") do |repo|
    git = lambda do |*args|
      output, error, status = Open3.capture3("git", "-C", repo, *args)
      raise error unless status.success?
      output
    end
    git.call("init", "-q")
    FileUtils.mkdir_p(File.join(repo, "app"))
    File.write(File.join(repo, "app/build.gradle.kts"), "versionName = \"0.1.1\"\n")
    %w[en-US ru-RU].each do |language|
      dir = File.join(repo, "fastlane/metadata/android", language)
      FileUtils.mkdir_p(dir)
      %w[title short_description full_description].each { |field| File.write(File.join(dir, "#{field}.txt"), "#{language} released #{field}\n") }
    end
    git.call("add", ".")
    git.call("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "Release fixture")
    git.call("tag", "v0.1.1")
    git.call("tag", "v0.2.0") # Deliberate versionName mismatch.
    full = File.join(repo, "fastlane/metadata/android/en-US/full_description.txt")
    File.write(full, "UNRELEASED WORKTREE TEXT")
    check = MetadataLaneCheck.new(fastfile, File.join(repo, "fastlane/Fastfile"))
    check.play_metadata(release_tag: "v0.1.1")
    options, files = check.uploads.fetch(0)
    assert(files.size == 6 && files.fetch("en-US/full_description.txt") == "en-US released full_description", "Must read tagged text, not worktree")
    %i[skip_upload_apk skip_upload_aab skip_upload_changelogs skip_upload_images skip_upload_screenshots changes_not_sent_for_review].each do |key|
      assert(options[key] == true, "Unsafe upload option: #{key}")
    end
    assert(options[:skip_upload_metadata] == false && options[:rescue_changes_not_sent_for_review] == false, "Must upload text without retrying automatic submission")
    assert(!File.exist?(options[:metadata_path]), "Temporary text must be cleaned up")
    rejects { check.play_metadata(release_tag: "main") }
    rejects { check.play_metadata(release_tag: "v0.9.9") }
    rejects { check.play_metadata(release_tag: "v0.2.0") }
    rejects { check.play_metadata(release_tag: "v0.1.1; echo injected") }
    rejects { check.play_metadata(release_tag: "v0.1.1", track: "production") }
    File.write(File.join(repo, "app/build.gradle.kts"), "versionName = \"0.1.2\"\n")
    File.delete(File.join(repo, "fastlane/metadata/android/ru-RU/title.txt"))
    git.call("add", ".")
    git.call("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false", "commit", "-qm", "Incomplete metadata")
    git.call("tag", "v0.1.2")
    rejects { check.play_metadata(release_tag: "v0.1.2") }
    ENV["SUPPLY_JSON_KEY_DATA"] = "{}"
    rejects { check.play_metadata(release_tag: "v0.1.1") }
    assert(check.uploads.size == 1, "Invalid input must not upload")
  end
ensure
  ENV["SUPPLY_JSON_KEY_DATA"] = previous_key
end
puts "Play metadata isolation checks passed"
