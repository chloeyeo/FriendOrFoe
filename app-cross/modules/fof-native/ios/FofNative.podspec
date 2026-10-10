Pod::Spec.new do |s|
  s.name           = 'FofNative'
  s.version        = '0.1.0'
  s.summary        = 'FriendOrFoe iOS camera pane with on-device Core ML detection'
  s.description    = s.summary
  s.license        = 'MIT'
  s.author         = 'FriendOrFoe team'
  s.homepage       = 'https://github.com/chloeyeo/FriendOrFoe'
  s.platforms      = { :ios => '17.0' }
  s.swift_version  = '5.9'
  s.source         = { git: 'https://github.com/chloeyeo/FriendOrFoe.git' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  s.source_files = '*.swift'
  # The compiled detectors (scripts/export-coreml.sh writes them; gitignored). Copied into the app bundle root.
  s.resources = ['Models/*.mlmodelc']
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_COMPILATION_MODE' => 'wholemodule'
  }
end
