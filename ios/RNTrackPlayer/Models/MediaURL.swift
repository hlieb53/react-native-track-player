//
//  MediaURL.swift
//  RNTrackPlayer
//
//  Created by David Chavez on 12.08.17.
//  Copyright © 2017 David Chavez. All rights reserved.
//

import Foundation

struct MediaURL {
    let value: URL
    let isLocal: Bool
    private let originalObject: Any
    
    init?(object: Any?) {
        guard let object = object else { return nil }
        originalObject = object
        
        if let localObject = object as? [String: Any] {
            isLocal = true
            let uri = localObject["uri"] as! String
            let encodedURI = uri.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)!
            value = URL(string: encodedURI)!
        } else {
            isLocal = false
            let url = object as! String
            let encodedURI = url.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed)!
            value = URL(string: encodedURI)!
        }
    }
}
